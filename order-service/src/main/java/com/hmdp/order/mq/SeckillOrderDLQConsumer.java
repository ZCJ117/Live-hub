package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 秒杀订单死信消费者（SPEC-04 §5.3 方案 A / 验收 A7）
 *
 * <p><b>为什么是 {@code %DLQ%seckill-order-consumer-group}</b>：RocketMQ 的自动死信队列命名规则是
 * {@code %DLQ%{consumerGroup}}，本项目 consumer group 为 {@code seckill-order-consumer-group}。
 * 原实现监听的是自定义 topic {@code seckill-order-dlq-topic}——该 topic **没有任何生产者**
 * （{@code sendToDeadLetterQueue} 零调用），于是：
 * 真正的死信进 {@code %DLQ%seckill-order-consumer-group} 无人订阅，代码监听的 topic 永远为空，
 * 整条兜底链**双向断开**，却制造了"有死信兜底"的假象。
 *
 * <p>泛型用 {@link MessageExt} 而非业务类型：只有它能拿到 msgId 与 {@code reconsumeTimes}。
 * 消费**不抛异常**——死信已经是最后一道网，再抛只会让它再次进入重试循环而无处可去。
 */
@Component
@RocketMQMessageListener(
        topic = SeckillOrderDLQConsumer.TOPIC_SECKILL_ORDER_DLQ,
        consumerGroup = "seckill-order-dlq-handler-group"
)
@Slf4j
public class SeckillOrderDLQConsumer implements RocketMQListener<MessageExt> {

    /** RocketMQ 自动死信队列：%DLQ% + 消费者组名 */
    public static final String TOPIC_SECKILL_ORDER_DLQ = "%DLQ%seckill-order-consumer-group";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillMetrics seckillMetrics;

    @Override
    public void onMessage(MessageExt message) {
        SeckillOrderMessage order = deserialize(message);
        if (order == null) {
            // 解析不出来也要留痕，否则这条死信就真的消失了
            log.error("死信消息无法解析，已丢弃需人工核查: msgId={}, reconsumeTimes={}, body={}",
                    message.getMsgId(), message.getReconsumeTimes(),
                    new String(message.getBody()));
            seckillMetrics.incrementDlqConsumed();
            return;
        }

        log.error("死信队列收到消息，订单处理失败需要人工干预: orderId={}, userId={}, voucherId={}, msgId={}, reconsumeTimes={}",
                order.getOrderId(), order.getUserId(), order.getVoucherId(),
                message.getMsgId(), message.getReconsumeTimes());

        try {
            String orderInfo = String.format("%d:%d:%d:%d",
                    order.getOrderId(), order.getUserId(), order.getVoucherId(),
                    System.currentTimeMillis());
            stringRedisTemplate.opsForList().rightPush(RedisConstants.SECKILL_PENDING_KEY, orderInfo);
            // 有界：待处理列表是人工处置队列，不能随故障持续无界增长（SPEC-04 §5.3 G7）
            stringRedisTemplate.opsForList().trim(RedisConstants.SECKILL_PENDING_KEY,
                    0, RedisConstants.SECKILL_LIST_MAX_SIZE - 1L);
            log.info("失败订单已记录到待处理列表: orderId={}", order.getOrderId());
        } catch (Exception e) {
            log.error("记录失败订单异常: orderId={}, error={}", order.getOrderId(), e.getMessage(), e);
        }
        seckillMetrics.incrementDlqConsumed();
    }

    private SeckillOrderMessage deserialize(MessageExt message) {
        try {
            return OBJECT_MAPPER.readValue(message.getBody(), SeckillOrderMessage.class);
        } catch (Exception e) {
            log.error("死信消息反序列化失败: msgId={}", message.getMsgId(), e);
            return null;
        }
    }
}
