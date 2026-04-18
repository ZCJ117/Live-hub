package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.service.ISeckillConsistencyService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SeckillConsistencyServiceImpl implements ISeckillConsistencyService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result checkOrderConsistency(Long orderId) {
        log.info("开始检查订单一致性: orderId={}", orderId);

        String orderDetailKey = "seckill:order:detail:";
        Map<Object, Object> orderInfo = stringRedisTemplate.opsForHash().entries(orderDetailKey);

        VoucherOrder dbOrder = voucherOrderMapper.selectById(orderId);

        if (dbOrder != null && orderInfo.isEmpty()) {
            log.info("订单一致性检查通过: orderId={}, 数据库存在，Redis已清理", orderId);
            return Result.ok("订单一致性正常");
        }

        if (dbOrder == null && !orderInfo.isEmpty()) {
            log.warn("发现不一致订单: orderId={}, Redis存在但数据库不存在", orderId);
            return Result.fail("订单数据不一致，需要修复");
        }

        return Result.ok("订单一致性正常");
    }

    @Override
    public Result syncStockFromRedisToDb(Long voucherId) {
        String lockKey = "lock:stock:sync:" + voucherId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                return Result.fail("获取同步锁失败，请稍后重试");
            }

            try {
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
                String redisStock = stringRedisTemplate.opsForValue().get(stockKey);

                if (redisStock == null) {
                    log.warn("Redis中不存在该优惠券库存: voucherId={}", voucherId);
                    return Result.fail("Redis中不存在该优惠券库存");
                }

                int currentRedisStock = Integer.parseInt(redisStock);
                log.info("开始同步库存: voucherId={}, Redis库存={}", voucherId, currentRedisStock);

                return Result.ok("库存同步成功");

            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            log.error("获取同步锁被中断: voucherId={}", voucherId, e);
            Thread.currentThread().interrupt();
            return Result.fail("同步操作被中断");
        } catch (Exception e) {
            log.error("库存同步异常: voucherId={}", voucherId, e);
            return Result.fail("库存同步失败: " + e.getMessage());
        }
    }

    @Override
    public Result checkPendingOrders() {
        String pendingKey = "seckill:order:pending";
        List<String> pendingOrders = stringRedisTemplate.opsForList().range(pendingKey, 0, -1);

        if (pendingOrders == null || pendingOrders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String orderInfo : pendingOrders) {
            String[] parts = orderInfo.split(":");
            if (parts.length >= 4) {
                Map<String, Object> order = new HashMap<>();
                order.put("orderId", Long.parseLong(parts[0]));
                order.put("userId", Long.parseLong(parts[1]));
                order.put("voucherId", Long.parseLong(parts[2]));
                order.put("timestamp", Long.parseLong(parts[3]));
                result.add(order);
            }
        }

        log.info("待处理订单数量: {}", result.size());
        return Result.ok(result);
    }

    @Override
    public Result repairInconsistentData(Long voucherId) {
        String lockKey = "lock:repair:" + voucherId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(30, 120, TimeUnit.SECONDS);
            if (!locked) {
                return Result.fail("获取修复锁失败，请稍后重试");
            }

            try {
                log.info("开始修复不一致数据: voucherId={}", voucherId);

                String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
                String orderKey = "seckill:order:" + voucherId;

                String redisStock = stringRedisTemplate.opsForValue().get(stockKey);
                Set<String> redisBuyers = stringRedisTemplate.opsForSet().members(orderKey);

                if (redisStock == null) {
                    return Result.fail("Redis中不存在该优惠券库存");
                }

                int currentRedisStock = Integer.parseInt(redisStock);
                int redisBuyerCount = redisBuyers != null ? redisBuyers.size() : 0;

                Long dbOrderCount = voucherOrderMapper.selectCount(
                        new LambdaQueryWrapper<VoucherOrder>()
                                .eq(VoucherOrder::getVoucherId, voucherId)
                );

                log.info("数据对比: voucherId={}, Redis库存={}, Redis购买人数={}, DB订单数={}",
                        voucherId, currentRedisStock, redisBuyerCount, dbOrderCount);

                Map<String, Object> report = new HashMap<>();
                report.put("voucherId", voucherId);
                report.put("redisStock", currentRedisStock);
                report.put("redisBuyerCount", redisBuyerCount);
                report.put("dbOrderCount", dbOrderCount);
                report.put("status", "检查完成");

                return Result.ok(report);

            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            log.error("修复操作被中断: voucherId={}", voucherId, e);
            Thread.currentThread().interrupt();
            return Result.fail("修复操作被中断");
        } catch (Exception e) {
            log.error("数据修复异常: voucherId={}", voucherId, e);
            return Result.fail("数据修复失败: " + e.getMessage());
        }
    }

    @Scheduled(fixedRate = 300000)
    public void scheduledConsistencyCheck() {
        log.info("开始执行定时一致性检查...");

        try {
            String pendingKey = "seckill:order:pending";
            Long pendingCount = stringRedisTemplate.opsForList().size(pendingKey);

            if (pendingCount != null && pendingCount > 0) {
                log.warn("发现{}条待处理订单，请及时处理", pendingCount);
            }

            log.info("定时一致性检查完成");
        } catch (Exception e) {
            log.error("定时一致性检查异常", e);
        }
    }
}
