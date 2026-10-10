package com.hmdp.order.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.FeignFailureRateMonitor;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
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
 *
 * <p><b>泛型为什么是 {@link MessageExt} 而不是业务类型</b>：只有 {@code MessageExt} 能拿到
 * RocketMQ 的**真实重试次数** {@code getReconsumeTimes()}。原实现读的是业务字段
 * {@code SeckillOrderMessage.retryCount}，它只在零调用的 {@code sendToDeadLetterQueue} 里自增，
 * 恒为 0——"重试已达上限，需要人工干预"的告警**永不打印**（SPEC-04 §5.4 / SPEC-08 §1.4）。
 */
@Component
@RocketMQMessageListener(
        topic = SeckillOrderProducer.TOPIC_SECKILL_ORDER,
        consumerGroup = "seckill-order-consumer-group",
        maxReconsumeTimes = 3
)
@Slf4j
public class SeckillOrderConsumer implements RocketMQListener<MessageExt> {

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

    @Resource
    private FeignFailureRateMonitor feignFailureRateMonitor;

    private static final int MAX_RETRY_COUNT = 3;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * RocketMQ 投递入口：这里只做反序列化与真实重试次数的提取。
     *
     * <p>反序列化失败无计可施（消息体已损坏），直接抛出让 RocketMQ 重试直至进死信队列
     * {@code %DLQ%seckill-order-consumer-group}，由 {@link SeckillOrderDLQConsumer} 兜底留痕。
     */
    @Override
    public void onMessage(MessageExt messageExt) {
        SeckillOrderMessage message;
        try {
            message = OBJECT_MAPPER.readValue(messageExt.getBody(), SeckillOrderMessage.class);
        } catch (Exception e) {
            seckillMetrics.incrementMqConsumeFail();
            throw new RuntimeException("秒杀订单消息反序列化失败: msgId=" + messageExt.getMsgId(), e);
        }
        handleOrder(message, messageExt.getReconsumeTimes());
    }

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
     * - 重试次数超过上限后告警并计入指标，随后抛出让框架投递到死信队列
     *
     * @param message        秒杀订单消息
     * @param reconsumeTimes RocketMQ 的真实重试次数（首次投递为 0）
     */
    public void handleOrder(SeckillOrderMessage message, int reconsumeTimes) {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        log.info("开始处理秒杀订单消息: orderId={}, userId={}, voucherId={}, reconsumeTimes={}",
                orderId, userId, voucherId, reconsumeTimes);

        String lockKey = "lock:order:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
            if (!locked) {
                // 不能静默 ACK：return 在 RocketMQ 语义下等于"消费成功"，
                // 消息不会重投、订单永久丢失且 Redis 预扣不回滚（SPEC-03 §1.4）
                throw new IllegalStateException("获取订单锁失败，触发重试: orderId=" + orderId);
            }

            try {
                VoucherOrder existingOrder = voucherOrderMapper.selectById(orderId);
                if (existingOrder != null) {
                    log.info("订单已存在，跳过处理: orderId={}", orderId);
                    seckillMetrics.incrementMqConsumeSuccess();
                    return;
                }

                // 释放墓碑（SPEC-14 §7 M7）：该单已被补偿器安全释放（库存已回补、用户已移出
                // seckill:order:{vid}），但消息可能仍在 broker 排队（消费端长时间宕机后恢复即是）。
                // 照常消费会在一份已回滚的预扣上重新建单，Redis 库存凭空多出 1，
                // 且 uk_user_voucher 拦不住（该用户此时无任何行）。直接 ACK 丢弃。
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.releasedKey(orderId)))) {
                    log.warn("订单已被安全释放，丢弃迟到消息: orderId={}, userId={}, voucherId={}",
                            orderId, userId, voucherId);
                    seckillMetrics.incrementMqConsumeReleased();
                    return;
                }

                Long count = voucherOrderMapper.selectCount(
                        new LambdaQueryWrapper<VoucherOrder>()
                                .eq(VoucherOrder::getUserId, userId)
                                .eq(VoucherOrder::getVoucherId, voucherId)
                );
                if (count > 0) {
                    log.warn("用户已购买过该优惠券，一人一单校验失败: userId={}, voucherId={}", userId, voucherId);
                    releaseUserMark(voucherId, userId);
                    seckillMetrics.incrementMqConsumeFail();
                    return;
                }

                Result deductResult;
                try {
                    deductResult = voucherFeignClient.deductStock(voucherId, orderId);
                    // SPEC-15 P1-4：只做观测，不改变控制流——
                    // 下面 catch 里的 throw 仍然是"应该重试"的正确语义（SPEC-03 §1.7）
                    feignFailureRateMonitor.record(true);
                } catch (Exception e) {
                    // 内部端点不可达/网络异常：属于"应该重试"，不能当作业务失败丢弃（SPEC-03 §1.7）
                    feignFailureRateMonitor.record(false);
                    log.error("调用库存扣减失败，触发重试: voucherId={}, orderId={}", voucherId, orderId, e);
                    throw new RuntimeException("库存服务调用失败", e);
                }
                if (deductResult == null || !Boolean.TRUE.equals(deductResult.getSuccess())) {
                    log.warn("扣减库存业务失败: voucherId={}, orderId={}, result={}",
                            voucherId, orderId, deductResult == null ? null : deductResult.getErrorMsg());
                    releaseUserMark(voucherId, userId);
                    seckillMetrics.incrementMqConsumeFail();
                    return;
                }

                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(orderId);
                voucherOrder.setUserId(userId);
                voucherOrder.setVoucherId(voucherId);
                voucherOrder.setStatus(1);

                try {
                    int insertResult = voucherOrderMapper.insert(voucherOrder);
                    if (insertResult <= 0) {
                        seckillMetrics.incrementMqConsumeFail();
                        throw new RuntimeException("订单插入失败: orderId=" + orderId);
                    }
                } catch (DuplicateKeyException e) {
                    // tb_voucher_order 的 uk_user_voucher（SPEC-02）拦下的并发重复：
                    // 幂等跳过，不重试。库存已在 (voucherId, orderId) 幂等保护下只扣一次。
                    log.warn("并发重复订单，幂等跳过: orderId={}, userId={}, voucherId={}",
                            orderId, userId, voucherId);
                    seckillMetrics.incrementMqConsumeSuccess();
                    return;
                }

                seckillMetrics.incrementMqConsumeSuccess();
                log.info("订单创建成功: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
                stringRedisTemplate.opsForHash().delete(
                        RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId, orderId.toString());

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

            // 用 RocketMQ 的真实重试次数。框架在 maxReconsumeTimes 耗尽后会把消息投到
            // %DLQ%seckill-order-consumer-group，这里先把告警与指标打出来（验收 A4）
            if (reconsumeTimes >= MAX_RETRY_COUNT) {
                log.error("订单处理重试次数已达上限，需要人工干预: orderId={}, reconsumeTimes={}",
                        orderId, reconsumeTimes);
                seckillMetrics.incrementRetryExhausted();
            }
            throw new RuntimeException("订单处理失败", e);
        }
    }

    /**
     * 消费侧业务失败回滚（SPEC-03 §5.6）：只移除用户标记。
     *
     * <p>库存**不恢复**——原实现的无条件 INCR 会在该用户此前已成功下单的场景下
     * 凭空多出库存，是超卖的潜在来源（SPEC-03 §1.6）。
     *
     * <p>已知取舍：本分支仅在 Redis 的 {@code seckill:order:{vid}} 集合丢失后可达
     * （否则 Lua 的 SISMEMBER 已在入口拦下）。此情形下保留 DECR 会让 Redis 库存偏少，
     * 方向上是少卖而非超卖，属安全侧。
     */
    private void releaseUserMark(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.opsForSet().remove(
                    RedisConstants.SECKILL_ORDER_SET_KEY + voucherId, userId.toString());
        } catch (Exception e) {
            log.error("移除用户秒杀标记失败: voucherId={}, userId={}", voucherId, userId, e);
        }
    }
}
