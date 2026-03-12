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
