package com.hmdp.agent.transfer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.mapper.AgentToolCallMapper;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.ticket.TicketPriorityRules;
import com.hmdp.agent.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转人工服务（FR-10 T4.8/T4.9）
 * 四类触发统一收口 trigger(reason)；触发后状态锁由 ChatOrchestratorService 在 doChat 入口执行；
 * D-5：默认无坐席 → 用户确认后走无人值守建单；移交包存 Redis（TTL 7 天）供 P2 工作台消费
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentSessionService sessionService;
    private final FlowStateService flowStateService;
    private final ChatMemoryService memoryService;
    private final AgentToolCallMapper toolCallMapper;
    private final TicketService ticketService;
    private final TrackEventService trackEventService;
    private final SseSessionManager sseManager;
    private final RedissonClient redisson;
    private final AgentProperties props;
    private final TicketPriorityRules priorityRules;

    public record TransferOutcome(boolean success, String message,
                                  String ticketNo, String expectedSla) {
    }

    /** 四类触发统一入口（幂等：已 TRANSFERRED/CLOSED 不重复触发） */
    public void trigger(AgentSession session, String reason) {
        if (!"ACTIVE".equals(session.getStatus())) {
            return;
        }
        sessionService.markTransferred(session.getId(), reason);
        session.setStatus("TRANSFERRED"); // 本地同步：本轮后续步骤立即感知状态锁
        flowStateService.setFlowState(session.getId(), "IDLE");
        trackEventService.track("m5_transfer_human", session.getId(), session.getUserId(),
                Map.of("transferReason", reason));
        pushTransferCard(session);
        log.info("已触发转人工: sessionId={}, reason={}", session.getId(), reason);
    }

    /** 转人工卡片：摘要预览四要素（身份/诉求/已查事实/未解决），折叠由前端渲染 */
    private void pushTransferCard(AgentSession session) {
        sseManager.send(session.getId(), "card", Map.of(
                "cardType", "TRANSFER_CONFIRM",
                "payload", Map.of(
                        "summaryPreview", session.getSummary() == null || session.getSummary().isBlank()
                                ? "（会话摘要生成中，将随对话自动补充）" : session.getSummary(),
                        "elements", List.of("身份", "诉求", "已查事实", "未解决问题"))));
    }

    /** 用户确认转人工 → 移交包 + 无人值守建单（D-5：seatOnline=false 直接建单告知时效） */
    public TransferOutcome confirmTransfer(Long userId, Long sessionId) {
        AgentSession session = sessionService.getOwned(sessionId, userId);
        if (!"TRANSFERRED".equals(session.getStatus())) {
            return new TransferOutcome(false, "会话未处于转人工状态", null, null);
        }
        if (props.getTransfer().isSeatOnline()) {
            // P2 工作台接入后的人工接管路径（本阶段不可达，占位保证协议完整）
            return new TransferOutcome(true, "已接入人工坐席，请稍候", null, null);
        }
        buildHandoverPackage(session); // 移交包（随工单供人工跟进）

        String summary = session.getSummary() == null || session.getSummary().isBlank()
                ? "用户申请转人工（原因：" + session.getTransferReason() + "），会话无摘要，详见移交包。"
                : session.getSummary();
        String priority = priorityRules.fundRelated(summary) ? "HIGH" : "MEDIUM";
        try {
            var ticket = ticketService.create(userId, sessionId, TicketRequest.of(
                    inferCategory(summary), priority, summary, Map.of()));
            String msg = "当前无人工坐席在线，已为您创建工单 " + ticket.getTicketNo()
                    + "（优先级：" + ticket.getPriority() + "），预计 " + ticket.getExpectedSla()
                    + " 内由人工跟进；等待期间您仍可继续留言，消息将随工单一并移交。";
            sseManager.send(sessionId, "delta", Map.of("text", msg));
            return new TransferOutcome(true, msg, ticket.getTicketNo(), ticket.getExpectedSla());
        } catch (Exception e) {
            log.error("无人值守建单失败: sessionId={}", sessionId, e);
            String msg = "转人工请求已受理，工单创建出现异常，客服会尽快与您联系。";
            sseManager.send(sessionId, "delta", Map.of("text", msg));
            return new TransferOutcome(true, msg, null, null);
        }
    }

    /** 移交包：完整历史 + 摘要 + 工具结果快照 + 触发原因 → Redis（TTL 7 天）→ snapshot_uri 记 key */
    public String buildHandoverPackage(AgentSession session) {
        Long sessionId = session.getId();
        List<Map<String, Object>> toolSnapshots = new ArrayList<>();
        toolCallMapper.selectList(Wrappers.<com.hmdp.agent.entity.AgentToolCall>lambdaQuery()
                        .eq(com.hmdp.agent.entity.AgentToolCall::getSessionId, sessionId)
                        .orderByAsc(com.hmdp.agent.entity.AgentToolCall::getCreateTime))
                .forEach(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("toolName", c.getToolName());
                    m.put("argsJson", c.getArgsJson());
                    m.put("resultSummary", c.getResultSummary());
                    m.put("success", c.getSuccess());
                    m.put("latencyMs", c.getLatencyMs());
                    toolSnapshots.add(m);
                });
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("sessionId", sessionId);
        pkg.put("userId", session.getUserId());
        pkg.put("transferReason", session.getTransferReason());
        pkg.put("summary", session.getSummary());
        pkg.put("history", memoryService.readRawJson(sessionId));
        pkg.put("toolSnapshots", toolSnapshots);
        pkg.put("builtAt", LocalDateTime.now().toString());

        String key = "agent:transfer:" + sessionId;
        try {
            redisson.<String>getBucket(key).set(MAPPER.writeValueAsString(pkg),
                    Duration.ofDays(props.getTransfer().getHandoverTtlDays()));
        } catch (Exception e) {
            log.error("移交包写入失败: sessionId={}", sessionId, e);
        }
        sessionService.updateSnapshotUri(sessionId, "redis://" + key);
        return key;
    }

    /** 摘要关键词粗分类（无人值守建单用；无法识别按 OTHER） */
    private String inferCategory(String summary) {
        if (summary == null) {
            return "OTHER";
        }
        if (summary.contains("退款") || summary.contains("订单") || summary.contains("券")) {
            return "ORDER";
        }
        if (summary.contains("商户") || summary.contains("店铺") || summary.contains("店")) {
            return "MERCHANT_SERVICE";
        }
        return "OTHER";
    }
}
