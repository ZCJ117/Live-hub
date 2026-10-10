package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Duration;

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
            writeBackToInFlightDetail(order);
            log.info("死信订单已回写在途明细，交补偿器接管: orderId={}", order.getOrderId());
        } catch (Exception e) {
            log.error("回写在途明细异常: orderId={}, error={}", order.getOrderId(), e.getMessage(), e);
        }
        seckillMetrics.incrementDlqConsumed();
    }

    /**
     * 死信单回写明细 Hash，交 {@code SeckillInFlightCompensator} 统一重投/释放
     * （SPEC-14 §2.2 第 3 点，D3 选 B：少一套数据结构）。
     *
     * <p><b>为什么 retryCount 必须继承而非重置</b>（SPEC-14 §7 M5）：明细 Hash 里已有该单的
     * 重投计数，若每次死信都从 0 重来，则「补偿器重投 → 消费再失败 → 再入 DLQ → 又重置」
     * 构成无限循环，§2.2 的「在途超时数随补偿收敛至 0」永不可达。
     *
     * <p>本改动改变了 SPEC-04 记录的「DLQ → seckill:order:pending」路径：
     * 现在 pending 只由补偿器的「重投耗尽 → 安全释放」写入，语义收敛为"需人工处置"。
     */
    private void writeBackToInFlightDetail(SeckillOrderMessage order) {
        String key = RedisConstants.detailKey(order.getVoucherId());
        String field = order.getOrderId().toString();

        int retryCount = 1;
        Object existing = stringRedisTemplate.opsForHash().get(key, field);
        if (existing != null) {
            try {
                JsonNode rc = OBJECT_MAPPER.readTree(existing.toString()).get("retryCount");
                retryCount = (rc == null ? 0 : rc.asInt(0)) + 1;
            } catch (Exception e) {
                log.warn("已有在途明细解析失败，retryCount 从 1 起算: field={}", field, e);
            }
        }

        String detail = String.format(
                "{\"voucherId\":\"%d\",\"userId\":\"%d\",\"orderId\":\"%d\",\"ts\":\"%d\",\"retryCount\":%d}",
                order.getVoucherId(), order.getUserId(), order.getOrderId(),
                System.currentTimeMillis(), retryCount);
        stringRedisTemplate.opsForHash().put(key, field, detail);
        stringRedisTemplate.expire(key, Duration.ofSeconds(RedisConstants.SECKILL_DETAIL_TTL_SECONDS));
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
