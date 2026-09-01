package com.hmdp.social.mq;

import com.hmdp.social.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工单路由通知消费（FR-09 T4.7）→ 站内信落库
 * 消费失败由 RocketMQ 重试（maxReconsumeTimes=3），耗尽进 DLQ（沿 order-service 先例）
 */
@Component
@RocketMQMessageListener(
        topic = "agent-m5-ticket-route",
        consumerGroup = "social-ticket-notify-group",
        maxReconsumeTimes = 3
)
@RequiredArgsConstructor
@Slf4j
public class TicketNotifyConsumer implements RocketMQListener<Map> {

    private final NotificationService notificationService;

    @SuppressWarnings("unchecked")
    @Override
    public void onMessage(Map message) {
        if (message == null || message.get("userId") == null || message.get("ticketNo") == null) {
            log.warn("工单通知消息缺关键字段，丢弃: {}", message);
            return;
        }
        long userId;
        long ticketId;
        try {
            userId = Long.parseLong(String.valueOf(message.get("userId")));
            ticketId = message.get("ticketId") == null ? 0L
                    : Long.parseLong(String.valueOf(message.get("ticketId")));
        } catch (NumberFormatException e) {
            log.warn("工单通知消息字段格式非法，丢弃: {}", message);
            return;
        }
        String ticketNo = String.valueOf(message.get("ticketNo"));
        String priority = String.valueOf(message.get("priority"));
        String sla = String.valueOf(message.get("expectedSla"));
        notificationService.saveNotification(userId, ticketId,
                "您的工单已受理",
                "工单 " + ticketNo + "（优先级：" + priority + "）已创建，预计 " + sla + " 内由人工跟进，可在\"我的-客服记录\"查看进度。");
    }
}
