package com.hmdp.agent.confirm;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.agent.dto.OrderCardDTO;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTask;
import com.hmdp.agent.mapper.AgentTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 确认卡片/退款任务服务（FR-08 T4.1，agent_task 全生命周期）
 * 幂等：actionId 唯一键 + 条件更新（DB 状态机原子流转，P4-R4）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConfirmTaskService {

    /** 卡片有效期（PRD FR-08：10 分钟） */
    private static final int EXPIRE_MINUTES = 10;
    /** 退款原因下拉枚举（PRD 卡片规范，附录 A） */
    public static final List<String> REFUND_REASONS = List.of("不要了", "未收到", "与描述不符", "其他");

    private final AgentTaskMapper taskMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 生成退款确认任务（PENDING_CONFIRM + actionId + 10min 有效期） */
    public AgentTask createRefundTask(AgentSession session, OrderCardDTO order) {
        AgentTask task = new AgentTask()
                .setSessionId(session.getId())
                .setUserId(session.getUserId())
                .setTaskType("REFUND_REQUEST")
                .setStatus("PENDING_CONFIRM")
                .setActionId(UUID.randomUUID().toString())
                .setBizOrderId(order.getOrderId())
                .setExpireTime(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES))
                .setPayloadJson(payloadOf(order));
        taskMapper.insert(task);
        log.info("退款确认任务已生成: taskId={}, sessionId={}, orderId={}, expire={}",
                task.getId(), session.getId(), order.getOrderId(), task.getExpireTime());
        return task;
    }

    /** 按幂等凭证查任务（userId 强制过滤：篡改/越权 actionId 查不到即拦截） */
    public Optional<AgentTask> findByActionId(Long userId, String actionId) {
        return taskMapper.selectList(Wrappers.<AgentTask>lambdaQuery()
                        .eq(AgentTask::getUserId, userId)
                        .eq(AgentTask::getActionId, actionId)
                        .last("LIMIT 1"))
                .stream()
                // 内存兜底过滤：mock 单测不模拟 SQL where，生产则双重防护越权
                .filter(t -> userId.equals(t.getUserId()))
                .findFirst();
    }

    /**
     * 条件更新抢确认（幂等核心）：仅 PENDING_CONFIRM 且未过期可流转 ADOPTED
     * @return true=当前请求胜出；false=已被并发请求消费/过期
     */
    public boolean tryAdopt(String actionId) {
        return taskMapper.update(null, Wrappers.<AgentTask>lambdaUpdate()
                .eq(AgentTask::getActionId, actionId)
                .eq(AgentTask::getStatus, "PENDING_CONFIRM")
                .gt(AgentTask::getExpireTime, LocalDateTime.now())
                .set(AgentTask::getStatus, "ADOPTED")
                .set(AgentTask::getConfirmTime, LocalDateTime.now())) > 0;
    }

    /** 确认失败/用户取消 → 卡片作废 */
    public void reject(AgentTask task) {
        taskMapper.update(null, Wrappers.<AgentTask>lambdaUpdate()
                .eq(AgentTask::getId, task.getId())
                .eq(AgentTask::getStatus, "PENDING_CONFIRM")
                .set(AgentTask::getStatus, "REJECTED"));
    }

    /** 懒过期：过期未确认 → EXPIRED（读时惰性触发，避免定时任务） */
    public boolean expireIfOverdue(AgentTask task) {
        if (!"PENDING_CONFIRM".equals(task.getStatus())
                || task.getExpireTime().isAfter(LocalDateTime.now())) {
            return false;
        }
        taskMapper.update(null, Wrappers.<AgentTask>lambdaUpdate()
                .eq(AgentTask::getId, task.getId())
                .eq(AgentTask::getStatus, "PENDING_CONFIRM")
                .set(AgentTask::getStatus, "EXPIRED"));
        return true;
    }

    /** 同订单进行中的任务（排除指定 taskId），支撑"重复申请返回已有编号"（T4.4） */
    public List<AgentTask> findActiveByOrder(Long userId, Long orderId, Long excludeTaskId) {
        return taskMapper.selectList(Wrappers.<AgentTask>lambdaQuery()
                .eq(AgentTask::getUserId, userId)
                .eq(AgentTask::getBizOrderId, orderId)
                .in(AgentTask::getStatus, "PENDING_CONFIRM", "ADOPTED")
                .ne(excludeTaskId != null, AgentTask::getId, excludeTaskId)
                .orderByDesc(AgentTask::getId))
                .stream()
                // 内存兜底：排除自身（mock 单测不模拟 SQL where）
                .filter(t -> !t.getId().equals(excludeTaskId))
                .toList();
    }

    private String payloadOf(OrderCardDTO order) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "orderId", order.getOrderId(),
                    "voucherId", order.getVoucherId() == null ? 0 : order.getVoucherId(),
                    "voucherTitle", order.getVoucherTitle() == null ? "" : order.getVoucherTitle(),
                    "payValue", order.getPayValue() == null ? 0 : order.getPayValue(),
                    "createTime", order.getCreateTime() == null ? "" : order.getCreateTime().toString(),
                    "reasons", REFUND_REASONS));
        } catch (Exception e) {
            return "{}";
        }
    }
}
