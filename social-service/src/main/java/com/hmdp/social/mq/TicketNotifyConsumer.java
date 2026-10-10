package com.hmdp.social.mq;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.social.service.NotificationService;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工单路由通知消费（FR-09 T4.7）→ 站内信落库
 * 消费失败由 RocketMQ 重试（maxReconsumeTimes=3），耗尽进 DLQ（沿 order-service 先例）
 *
 * <p>实现 {@code RocketMQListener<MessageExt>} 而非 {@code <Map>}：msgId 与 reconsumeTimes
 * 只存在于原始消息上，RocketMQ 反序列化后的业务体拿不到它们，§5.4 幂等与 §5.5 重试上限
 * 就无处挂载。故自行在 {@link #onMessage} 里解出业务 Map 再交给 {@link #handleTicket}。
 */
@Component
@RocketMQMessageListener(
        topic = "agent-m5-ticket-route",
        consumerGroup = "social-ticket-notify-group",
        maxReconsumeTimes = 3
)
@RequiredArgsConstructor
@Slf4j
public class TicketNotifyConsumer implements RocketMQListener<MessageExt> {

    /** 与 maxReconsumeTimes 一致：重试到此次数即认为已耗尽，需人工介入 */
    private static final int MAX_RECONSUME_TIMES = 3;
    /** 去重标记 TTL（小时）：长于重试窗口即可，过期后同 msgId 再投递也已过重试期 */
    private static final long DEDUP_TTL_HOURS = 24L;
    /** 文案兜底值：缺字段时不允许把字面量 "null" 写进站内信（BUG-06） */
    private static final String UNSPECIFIED = "未指定";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotificationService notificationService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(MessageExt messageExt) {
        Map<String, Object> message;
        try {
            // noinspection unchecked
            message = MAPPER.readValue(messageExt.getBody(), Map.class);
        } catch (Exception e) {
            // 消息体格式不合法，重试也不会变好：丢弃并留痕，不进 DLQ 噪声
            log.error("工单通知消息反序列化失败，丢弃: msgId={}", messageExt.getMsgId(), e);
            return;
        }
        handleTicket(messageExt, message);
    }

    /**
     * 包可见以便单测直接投递（业务 Map + MessageExt 一并传入）。
     */
    public void handleTicket(MessageExt messageExt, Map<String, Object> message) {
        // SPEC-08 §5.5：真实重试次数只在 MessageExt 上；达上限说明反复失败，告警待人工
        if (messageExt.getReconsumeTimes() >= MAX_RECONSUME_TIMES) {
            log.error("工单通知消费重试已达上限，需人工干预: msgId={}, reconsumeTimes={}",
                    messageExt.getMsgId(), messageExt.getReconsumeTimes());
        }

        // SPEC-08 §5.4：以 msgId 做前置去重，重复投递直接跳过（Redis 写失败会抛，交 MQ 重试）
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisConstants.MQ_DEDUP_KEY + messageExt.getMsgId(), "1",
                        DEDUP_TTL_HOURS, TimeUnit.HOURS);
        if (!Boolean.TRUE.equals(first)) {
            log.info("重复消息，幂等跳过: msgId={}", messageExt.getMsgId());
            return;
        }

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
        // BUG-06：String.valueOf((Object)null) 得到的是字符串 "null"，blankToDefault 只认空串/空白，
        // 故先判空再兜底，否则字面量 "null" 会照旧写进站内信
        String priority = textOrDefault(message.get("priority"));
        String sla = textOrDefault(message.get("expectedSla"));
        notificationService.saveNotification(userId, ticketId,
                "您的工单已受理",
                "工单 " + ticketNo + "（优先级：" + priority + "）已创建，预计 " + sla + " 内由人工跟进，可在\"我的-客服记录\"查看进度。");
    }

    /** 空值/空白统一兜底为「未指定」，避免字面量 "null" 进入面客文案 */
    private static String textOrDefault(Object value) {
        return StrUtil.blankToDefault(value == null ? null : String.valueOf(value), UNSPECIFIED);
    }
}
