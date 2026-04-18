package com.hmdp.order.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.order.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.micrometer.core.instrument.Timer;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * 优惠券订单服务实现类
 * 
 * 负责处理优惠券秒杀的核心业务逻辑，包括：
 * 1. 秒杀资格校验（通过Lua脚本保证原子性）
 * 2. 异步订单处理（通过消息队列削峰）
 * 3. 秒杀指标监控（记录成功/失败等关键指标）
 * 
 * 采用"Redis预扣库存 + MQ异步下单"的架构，保证高并发下的系统稳定性和数据最终一致性。
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillMetrics seckillMetrics;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀优惠券入口方法
     * 
     * 处理流程：
     * 1. 生成唯一订单ID（使用Redis ID生成器）
     * 2. 执行Lua脚本进行原子性库存预扣减和一人一单校验
     * 3. 根据脚本返回结果处理成功/失败逻辑
     * 4. 发送MQ消息异步创建订单（实现流量削峰）
     * 5. 记录秒杀相关指标（成功率、延迟等）
     * 
     * @param voucherId 优惠券ID
     * @return Result 包含订单ID（成功）或错误信息（失败）
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Timer.Sample timerSample = seckillMetrics.startTimer();
        seckillMetrics.incrementSeckillRequest();

        try {
            Long userId = UserHolder.getUser().getId();

            long orderId = redisIdWorker.nextId("order");

            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString(), String.valueOf(orderId));

            if (result != 0) {
                seckillMetrics.incrementSeckillFail();
                if (result == 1) {
                    seckillMetrics.incrementStockInsufficient();
                    log.warn("秒杀失败-库存不足: userId={}, voucherId={}", userId, voucherId);
                    return Result.fail("库存不足");
                } else {
                    seckillMetrics.incrementDuplicateOrder();
                    log.warn("秒杀失败-重复下单: userId={}, voucherId={}", userId, voucherId);
                    return Result.fail("不能重复下单");
                }
            }

            SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);

            boolean sendSuccess = seckillOrderProducer.sendSeckillOrderMessageAsync(message);
            if (sendSuccess) {
                seckillMetrics.incrementMqSendSuccess();
            } else {
                seckillMetrics.incrementMqSendFail();
                log.warn("消息发送失败，但Redis已预扣库存，订单将异步处理: orderId={}", orderId);
            }

            seckillMetrics.incrementSeckillSuccess();
            log.info("秒杀资格校验通过，订单异步处理中: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);

            return Result.ok(orderId);
        } finally {
            seckillMetrics.recordLatency(timerSample);
        }
    }

    /**
     * 创建优惠券订单（供传统同步流程使用）
     * 
     * 注意：此方法在秒杀场景中已由异步流程替代，仅保留用于兼容传统调用。
     * 方法通过分布式事务（Seata）保证数据库操作和库存扣减的一致性。
     * 
     * @param voucherOrder 优惠券订单实体
     */
    @GlobalTransactional(name = "createVoucherOrder", rollbackFor = Exception.class)
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }

        Result result = voucherFeignClient.deductStock(voucherOrder.getVoucherId());
        if (!result.getSuccess()) {
            log.error("库存不足！");
            return;
        }

        save(voucherOrder);
    }
}
