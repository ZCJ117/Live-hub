package com.hmdp.agent.ticket;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.exception.BusinessException;
import com.hmdp.agent.mapper.AgentTicketMapper;
import com.hmdp.agent.mq.TicketNotifyProducer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 工单基础服务（Phase 2：创建/查询/更新/状态流转）
 * Phase 4 将在其上叠加 create_ticket 工具（要素收集 + LLM 摘要 + MQ 通知）
 */
@Service
@Slf4j
public class TicketService extends ServiceImpl<AgentTicketMapper, AgentTicket> {

    private final RedissonClient redisson;
    /** MQ 生产者缺席时（RocketMQ 离线）为 null，工单创建不受影响（P4-R5 解耦） */
    private final TicketNotifyProducer notifyProducer;

    public TicketService(RedissonClient redisson,
                         @Autowired(required = false) TicketNotifyProducer notifyProducer) {
        this.redisson = redisson;
        this.notifyProducer = notifyProducer;
    }

    /** 工单号前缀（工单号 = TK + yyyyMMdd + 6位日序号；外部幂等判定引用此常量） */
    public static final String TICKET_NO_PREFIX = "TK";

    /** 工单状态机：OPEN→ROUTED→RESOLVED（非法跳转 100% 拦截，FR-14 验收口径） */
    private static final Map<String, String> TRANSITIONS = Map.of(
            "OPEN", "ROUTED",
            "ROUTED", "RESOLVED");

    private static final Map<String, String> CATEGORY_GROUP = Map.of(
            "ORDER", "ORDER_GROUP",
            "VOUCHER", "MARKETING_GROUP",
            "MERCHANT_SERVICE", "MERCHANT_GROUP",
            "ACCOUNT_SECURITY", "SECURITY_GROUP",
            "OTHER", "ORDER_GROUP");

    /** 创建工单：分类路由 + 优先级规则 + dedup_key 去重 + 工单号生成 */
    public AgentTicket create(Long userId, Long sessionId, TicketRequest req) {
        String priority = req.getPriority() != null ? req.getPriority() : defaultPriority(req.getCategory());
        String assigneeGroup = CATEGORY_GROUP.getOrDefault(req.getCategory(), "ORDER_GROUP");
        String sla = switch (priority) {
            case "HIGH" -> "4h";
            case "LOW" -> "72h";
            default -> "24h";
        };
        String dedupKey = buildDedupKey(sessionId, req.getCategory(), req.getRefs());

        // 同会话同问题去重（FR-09 边界：返回已有工单号）
        AgentTicket existing = getOne(Wrappers.<AgentTicket>lambdaQuery()
                .eq(AgentTicket::getDedupKey, dedupKey)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("工单去重命中: dedupKey={}, ticketNo={}", dedupKey, existing.getTicketNo());
            return existing;
        }

        AgentTicket ticket = new AgentTicket()
                .setTicketNo(nextTicketNo())
                .setSessionId(sessionId)
                .setUserId(userId)
                .setCategory(req.getCategory())
                .setPriority(priority)
                .setSummary(req.getSummary())
                .setRefsJson(toJson(req.getRefs()))
                .setDedupKey(dedupKey)
                .setStatus("OPEN")
                .setAssigneeGroup(assigneeGroup)
                .setExpectedSla(sla)
                .setNotifyStatus("PENDING");
        save(ticket);
        // T4.7：路由通知（工单创建与通知解耦；失败仅记 notify_status，不回滚工单）
        if (notifyProducer != null) {
            boolean sent;
            try {
                sent = notifyProducer.sendRouteNotify(ticket);
            } catch (Exception e) {
                sent = false;
            }
            ticket.setNotifyStatus(sent ? "SENT" : "FAILED");
            updateById(ticket);
        }
        log.info("工单创建: ticketNo={}, category={}, priority={}, group={}",
                ticket.getTicketNo(), ticket.getCategory(), priority, assigneeGroup);
        return ticket;
    }

    /** 凭工单号查询（FR-13 前置：工单号唯一可查）；归属校验防越权 */
    public AgentTicket getByTicketNo(Long userId, String ticketNo) {
        AgentTicket ticket = getOne(Wrappers.<AgentTicket>lambdaQuery()
                .eq(AgentTicket::getTicketNo, ticketNo)
                .last("LIMIT 1"));
        if (ticket == null || !ticket.getUserId().equals(userId)) {
            throw new BusinessException("工单不存在或无权访问");
        }
        return ticket;
    }

    /** 我的工单列表（FR-13：我的工单） */
    public java.util.List<AgentTicket> listByUser(Long userId) {
        return list(Wrappers.<AgentTicket>lambdaQuery()
                .eq(AgentTicket::getUserId, userId)
                .orderByDesc(AgentTicket::getCreateTime));
    }

    /**
     * 状态流转：仅允许 OPEN→ROUTED→RESOLVED，非法跳转抛业务异常
     * RESOLVED 时记录处理结果与时间
     */
    public AgentTicket transition(Long userId, String ticketNo, String targetStatus, String handleResult) {
        AgentTicket ticket = getByTicketNo(userId, ticketNo);
        return doTransition(ticket, targetStatus, handleResult);
    }

    /**
     * D9 工作台：坐席侧状态流转（与用户侧同一状态机，非法跳转同样 100% 拦截；
     * 不校验工单归属——坐席处理全组工单，PRD 未定义坐席角色体系，演示级）
     */
    public AgentTicket transitionBySeat(String ticketNo, String targetStatus, String handleResult) {
        java.util.List<AgentTicket> found = list(Wrappers.<AgentTicket>lambdaQuery()
                .eq(AgentTicket::getTicketNo, ticketNo)
                .last("LIMIT 1"));
        if (found.isEmpty()) {
            throw new BusinessException("工单不存在");
        }
        return doTransition(found.get(0), targetStatus, handleResult);
    }

    /** 状态机核心（用户侧/坐席侧共用） */
    private AgentTicket doTransition(AgentTicket ticket, String targetStatus, String handleResult) {
        String allowed = TRANSITIONS.get(ticket.getStatus());
        if (!ticket.getStatus().equals(targetStatus) && !targetStatus.equals(allowed)) {
            throw new BusinessException("非法的状态流转: " + ticket.getStatus() + " → " + targetStatus);
        }
        ticket.setStatus(targetStatus);
        if ("RESOLVED".equals(targetStatus)) {
            ticket.setHandleResult(handleResult == null ? "" : handleResult)
                    .setResolveTime(LocalDateTime.now());
        }
        updateById(ticket);
        return ticket;
    }

    /** D9 工作台：坐席侧工单查询（组过滤 + priority HIGH>MEDIUM>LOW 排序，同级按创建时间倒序） */
    public java.util.List<AgentTicket> listForConsole(String group, String priority, String status) {
        java.util.List<AgentTicket> tickets = list(Wrappers.<AgentTicket>lambdaQuery()
                .eq(group != null && !group.isBlank(), AgentTicket::getAssigneeGroup, group)
                .eq(priority != null && !priority.isBlank(), AgentTicket::getPriority, priority)
                .eq(status != null && !status.isBlank(), AgentTicket::getStatus, status));
        Map<String, Integer> rank = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
        tickets.sort(java.util.Comparator
                .comparingInt((AgentTicket t) -> rank.getOrDefault(t.getPriority(), 3))
                .thenComparing(t -> t.getCreateTime(), java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return tickets;
    }

    /** priority 默认规则（FR-09：涉资金=高，普通=中，建议=低） */
    private String defaultPriority(String category) {
        // Phase 4 由 LLM 摘要 + 规则引擎（涉资金关键词）判定；本阶段按类别给默认值
        return switch (category) {
            case "ACCOUNT_SECURITY" -> "HIGH";
            case "OTHER" -> "LOW";
            default -> "MEDIUM";
        };
    }

    /** dedup_key = MD5(category + sorted(refs) + sessionId)（PRD 6.1 补充字段） */
    static String buildDedupKey(Long sessionId, String category, Map<String, Object> refs) {
        TreeMap<String, Object> sorted = new TreeMap<>(refs == null ? Map.of() : refs);
        String raw = category + "|" + sorted + "|" + sessionId;
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // MD5 不可用时用 hash 兜底（仅作去重键，无安全要求）
            return String.valueOf(raw.hashCode());
        }
    }

    /** 工单号：TK + yyyyMMdd + 6位日序号（Redis INCR，当日唯一） */
    private String nextTicketNo() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = redisson.getAtomicLong("agent:ticket:seq:" + date).incrementAndGet();
        return TICKET_NO_PREFIX + date + String.format("%06d", seq);
    }

    private String toJson(Map<String, Object> refs) {
        if (refs == null || refs.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(refs);
        } catch (Exception e) {
            return null;
        }
    }
}
