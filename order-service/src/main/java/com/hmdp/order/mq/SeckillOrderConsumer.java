package com.hmdp.order.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消息消费者
 * 
 * 负责消费秒杀资格校验通过后产生的订单消息，完成最终的下单操作。
 * 核心职责：
 * 1. 保证消息消费的幂等性（通过订单ID去重）
 * 2. 执行最终的一致性检查（一人一单、库存扣减）
 * 3. 处理异常情况并回滚Redis预扣数据
 * 4. 记录消费指标用于监控
 * 
 * 采用分布式锁保证同一订单的串行处理，避免重复消费导致的数据不一致问题。
 */
@Component
@RocketMQMessageListener(
        topic = SeckillOrderProducer.TOPIC_SECKILL_ORDER,
        consumerGroup = "seckill-order-consumer-group",
        maxReconsumeTimes = 3
)
@Slf4j
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SeckillMetrics seckillMetrics;

    private static final int MAX_RETRY_COUNT = 3;

    /**
     * 处理秒杀订单消息
     * 
     * 消费流程：
     * 1. 获取分布式锁，保证同一订单的串行处理
     * 2. 检查订单是否已存在（幂等性保障）
     * 3. 二次校验一人一单规则（防止极端情况下的并发问题）
     * 4. 调用库存服务扣减数据库库存
     * 5. 创建订单记录到数据库
     * 6. 清理Redis中的临时订单数据
     * 
     * 异常处理：
     * - 任何步骤失败都会回滚Redis预扣数据
     * - 重试次数超过上限后需要人工干预
     * 
     * @param message 秒杀订单消息
     */
    @Override
    public void onMessage(SeckillOrderMessage message) {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        log.info("开始处理秒杀订单消息: orderId={}, userId={}, voucherId={}, retryCount={}",
                orderId, userId, voucherId, message.getRetryCount());

        String lockKey = "lock:order:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取订单锁失败，可能正在处理中: orderId={}", orderId);
                return;
            }

            try {
                VoucherOrder existingOrder = voucherOrderMapper.selectById(orderId);
                if (existingOrder != null) {
                    log.info("订单已存在，跳过处理: orderId={}", orderId);
                    seckillMetrics.incrementMqConsumeSuccess();
                    return;
                }

                Long count = voucherOrderMapper.selectCount(
                        new LambdaQueryWrapper<VoucherOrder>()
                                .eq(VoucherOrder::getUserId, userId)
                                .eq(VoucherOrder::getVoucherId, voucherId)
                );
                if (count > 0) {
                    log.warn("用户已购买过该优惠券，一人一单校验失败: userId={}, voucherId={}", userId, voucherId);
                    rollbackRedisData(voucherId, userId);
                    seckillMetrics.incrementMqConsumeFail();
                    return;
                }

                Result deductResult = voucherFeignClient.deductStock(voucherId);
                if (!deductResult.getSuccess()) {
                    log.error("扣减库存失败: voucherId={}, result={}", voucherId, deductResult.getErrorMsg());
                    rollbackRedisData(voucherId, userId);
                    seckillMetrics.incrementMqConsumeFail();
                    return;
                }

                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(orderId);
                voucherOrder.setUserId(userId);
                voucherOrder.setVoucherId(voucherId);
                voucherOrder.setStatus(1);

                int insertResult = voucherOrderMapper.insert(voucherOrder);
                if (insertResult > 0) {
                    seckillMetrics.incrementMqConsumeSuccess();
                    log.info("订单创建成功: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
                    stringRedisTemplate.opsForHash().delete(
                            RedisConstants.SECKILL_STOCK_KEY + "order:detail:" + voucherId,
                            orderId.toString()
                    );
                } else {
                    log.error("订单插入失败: orderId={}", orderId);
                    seckillMetrics.incrementMqConsumeFail();
                    throw new RuntimeException("订单插入失败");
                }

            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

        } catch (InterruptedException e) {
            log.error("获取锁被中断: orderId={}", orderId, e);
            Thread.currentThread().interrupt();
            seckillMetrics.incrementMqConsumeFail();
            throw new RuntimeException("获取锁被中断", e);
        } catch (Exception e) {
            log.error("处理秒杀订单消息异常: orderId={}, error={}", orderId, e.getMessage(), e);
            seckillMetrics.incrementMqConsumeFail();

            if (message.getRetryCount() >= MAX_RETRY_COUNT) {
                log.error("订单处理重试次数已达上限，需要人工干预: orderId={}, retryCount={}",
                        orderId, message.getRetryCount());
            }
            throw new RuntimeException("订单处理失败", e);
        }
    }

    /**
     * 回滚Redis预扣数据
     * 
     * 在消息消费失败时调用，用于恢复Redis中的库存和用户购买记录。
     * 保证Redis预扣数据与数据库最终状态的一致性。
     * 
     * @param voucherId 优惠券ID
     * @param userId 用户ID
     */
    private void rollbackRedisData(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            log.info("Redis数据回滚成功: voucherId={}, userId={}", voucherId, userId);
        } catch (Exception e) {
            log.error("Redis数据回滚失败: voucherId={}, userId={}, error={}", voucherId, userId, e.getMessage(), e);
        }
    }
}
