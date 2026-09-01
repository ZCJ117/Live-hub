package com.hmdp.agent.mq;

import com.hmdp.agent.entity.AgentTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工单路由通知生产者（FR-09 T4.7）
 * 仅在 RocketMQTemplate 存在时生效（MQ 离线 → 工单创建不受影响，notify_status 保持 PENDING，P4-R5 解耦原则）
 */
@Component
@ConditionalOnBean(RocketMQTemplate.class)
@Slf4j
@RequiredArgsConstructor
public class TicketNotifyProducer {

    public static final String TOPIC = "agent-m5-ticket-route";

    private final RocketMQTemplate rocketMQTemplate;

    /** @return true=发送成功（调用方据此写 notify_status） */
    public boolean sendRouteNotify(AgentTicket ticket) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ticketId", ticket.getId());
        payload.put("ticketNo", ticket.getTicketNo());
        payload.put("userId", ticket.getUserId());
        payload.put("category", ticket.getCategory());
        payload.put("priority", ticket.getPriority());
        payload.put("assigneeGroup", ticket.getAssigneeGroup());
        payload.put("expectedSla", ticket.getExpectedSla());
        payload.put("summary", ticket.getSummary());
        try {
            rocketMQTemplate.convertAndSend(TOPIC, payload);
            log.info("工单路由通知已发送: ticketNo={}, group={}", ticket.getTicketNo(), ticket.getAssigneeGroup());
            return true;
        } catch (Exception e) {
            log.error("工单路由通知发送失败: ticketNo={}", ticket.getTicketNo(), e);
            return false;
        }
    }
}
