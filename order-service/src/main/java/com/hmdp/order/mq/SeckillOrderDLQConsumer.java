package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
@RocketMQMessageListener(
        topic = SeckillOrderProducer.TOPIC_SECKILL_ORDER_DLQ,
        consumerGroup = "seckill-order-dlq-consumer-group"
)
@Slf4j
public class SeckillOrderDLQConsumer implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(SeckillOrderMessage message) {
        log.error("死信队列收到消息，订单处理失败需要人工干预: orderId={}, userId={}, voucherId={}, retryCount={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId(), message.getRetryCount());

        try {
            String pendingOrderKey = "seckill:order:pending";
            String orderInfo = String.format("%d:%d:%d:%d",
                    message.getOrderId(),
                    message.getUserId(),
                    message.getVoucherId(),
                    System.currentTimeMillis());
            stringRedisTemplate.opsForList().rightPush(pendingOrderKey, orderInfo);
            log.info("失败订单已记录到待处理列表: orderId={}", message.getOrderId());
        } catch (Exception e) {
            log.error("记录失败订单异常: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
        }
    }
}
