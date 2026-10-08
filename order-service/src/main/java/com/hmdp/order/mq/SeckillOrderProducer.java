package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 秒杀订单消息生产者
 * 
 * 负责将秒杀资格校验通过的订单发送到消息队列，实现异步下单流程。
 * 核心作用：
 * 1. 流量削峰：将瞬时高并发请求转化为异步消息处理
 * 2. 解耦：分离秒杀资格校验和订单创建两个关键步骤
 * 3. 可靠性：提供同步发送与异步发送两种模式；**发送失败不重试、无补偿**，
 *    由调用方决定回滚（见 sendSeckillOrderMessage 的返回值语义）
 *
 * 消息主题：
 * - seckill-order-topic: 正常秒杀订单处理
 * - seckill-order-dlq-topic: 死信队列（消费端见 SeckillOrderDLQConsumer）
 */
@Component
@Slf4j
public class SeckillOrderProducer {

    public static final String TOPIC_SECKILL_ORDER = "seckill-order-topic";

    public static final String TOPIC_SECKILL_ORDER_DLQ = "seckill-order-dlq-topic";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 同步发送秒杀订单消息
     *
     * 适用于需要立即确认发送结果的场景，保证消息可靠性。
     * 如果发送失败，会立即返回false，调用方可以相应处理。
     * <p>秒杀主链路必须使用本方法：只有它能让调用方在失败时回滚 Redis 预扣并返回失败。
     *
     * @param message 秒杀订单消息
     * @return true-发送成功，false-发送失败
     */
    public boolean sendSeckillOrderMessage(SeckillOrderMessage message) {
        try {
            rocketMQTemplate.syncSend(
                    TOPIC_SECKILL_ORDER,
                    MessageBuilder.withPayload(message).build(),
                    3000
            );
            log.info("秒杀订单消息发送成功: orderId={}, userId={}, voucherId={}",
                    message.getOrderId(), message.getUserId(), message.getVoucherId());
            return true;
        } catch (Exception e) {
            log.error("秒杀订单消息发送失败: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 异步发送秒杀订单消息（**不保证投递**）
     *
     * <p>返回值语义是"提交成功"，**不代表发送成功**——真正的失败被吞在 onException 回调里，
     * 调用方无法据此决策。因此本方法**不得**用于秒杀主链路，主链路必须使用
     * {@link #sendSeckillOrderMessage}（syncSend），否则 Redis 已预扣而消息未投递，
     * 用户会拿到一个永不兑现的 orderId（SPEC-03 §1.3）。
     *
     * @param message 秒杀订单消息
     * @return true-已提交（不代表已投递），false-提交失败
     */
    public boolean sendSeckillOrderMessageAsync(SeckillOrderMessage message) {
        try {
            rocketMQTemplate.asyncSend(
                    TOPIC_SECKILL_ORDER,
                    MessageBuilder.withPayload(message).build(),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.info("秒杀订单消息异步发送成功: orderId={}, msgId={}",
                                    message.getOrderId(), sendResult.getMsgId());
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("秒杀订单消息异步发送失败: orderId={}, error={}",
                                    message.getOrderId(), e.getMessage(), e);
                        }
                    },
                    3000
            );
            return true;
        } catch (Exception e) {
            log.error("秒杀订单消息异步发送异常: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
            return false;
        }
    }
}
