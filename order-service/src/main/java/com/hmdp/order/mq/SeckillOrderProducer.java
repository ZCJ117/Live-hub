package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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
 * 3. 可靠性：提供同步发送和异步发送两种模式，支持重试机制
 * 
 * 消息主题：
 * - seckill-order-topic: 正常秒杀订单处理
 * - seckill-order-dlq-topic: 死信队列，处理失败消息
 * - stock-sync-topic: 库存同步主题（用于一致性保证）
 */
@Component
@Slf4j
public class SeckillOrderProducer {

    public static final String TOPIC_SECKILL_ORDER = "seckill-order-topic";

    public static final String TOPIC_SECKILL_ORDER_DLQ = "seckill-order-dlq-topic";

    public static final String TOPIC_STOCK_SYNC = "stock-sync-topic";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 同步发送秒杀订单消息
     * 
     * 适用于需要立即确认发送结果的场景，保证消息可靠性。
     * 如果发送失败，会立即返回false，调用方可以相应处理。
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
     * 异步发送秒杀订单消息
     * 
     * 适用于高并发场景，不阻塞主线程，通过回调函数处理发送结果。
     * 即使发送失败，也不会影响用户秒杀资格（Redis已预扣库存）。
     * 
     * @param message 秒杀订单消息
     * @return true-提交成功（不代表发送成功），false-提交失败
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

    public void sendToDeadLetterQueue(SeckillOrderMessage message, String reason) {
        try {
            message.setRetryCount(message.getRetryCount() + 1);
            rocketMQTemplate.syncSend(
                    TOPIC_SECKILL_ORDER_DLQ,
                    MessageBuilder.withPayload(message)
                            .setHeader("reason", reason)
                            .setHeader("retryCount", message.getRetryCount())
                            .build()
            );
            log.warn("订单消息发送到死信队列: orderId={}, reason={}, retryCount={}",
                    message.getOrderId(), reason, message.getRetryCount());
        } catch (Exception e) {
            log.error("发送到死信队列失败: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
        }
    }

    public void sendStockSyncMessage(Long voucherId, Integer stock) {
        try {
            rocketMQTemplate.asyncSend(
                    TOPIC_STOCK_SYNC,
                    MessageBuilder.withPayload(new StockSyncMessage(voucherId, stock)).build(),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.info("库存同步消息发送成功: voucherId={}, stock={}", voucherId, stock);
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("库存同步消息发送失败: voucherId={}, error={}", voucherId, e.getMessage());
                        }
                    },
                    3000
            );
        } catch (Exception e) {
            log.error("库存同步消息发送异常: voucherId={}, error={}", voucherId, e.getMessage(), e);
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StockSyncMessage {
        private Long voucherId;
        private Integer stock;
    }
}
