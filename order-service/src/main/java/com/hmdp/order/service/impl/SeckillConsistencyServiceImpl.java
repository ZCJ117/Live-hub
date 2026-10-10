package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.entity.SeckillConsistencyAudit;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.SeckillConsistencyAuditMapper;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀一致性对账与补偿实现（SPEC-04 §5.5 / §5.6）
 *
 * <p><b>本次修复的三个"空壳"</b>：
 * <ol>
 *   <li>{@code syncStockFromRedisToDb} 原为「读 Redis + 打日志 + 返回『库存同步成功』」，**零 DB 写入**——
 *       运维据此认为已对账，实际分歧原封不动（比不实现更危险）；</li>
 *   <li>{@code repairInconsistentData} 原为只读统计（{@code status} 写死「检查完成」），无任何写入；</li>
 *   <li>{@code checkOrderConsistency} 原读 {@code seckill:order:detail:}（缺 voucherId 后缀）且用
 *       {@code entries()} 读整表，{@code orderInfo} 恒为空 Map → **永远返回「订单一致性正常」**。</li>
 * </ol>
 */
@Service
@Slf4j
public class SeckillConsistencyServiceImpl implements ISeckillConsistencyService {

    /** 与同步接口共用的锁前缀，保证多实例/多入口不会并发改写同一张券的库存 */
    private static final String STOCK_SYNC_LOCK = "lock:stock:sync:";
    private static final String REPAIR_LOCK = "lock:repair:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private SeckillConsistencyAuditMapper auditMapper;

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private RedissonClient redissonClient;

    // ------------------------------------------------------------------ 对账

    @Override
    public Result checkOrderConsistency(Long orderId, Long voucherId) {
        Long vid = voucherId != null ? voucherId : resolveVoucherId(orderId);
        if (vid == null) {
            return Result.fail("无法定位订单 " + orderId + " 所属的秒杀券，请显式传入 voucherId");
        }
        log.info("开始检查订单一致性: orderId={}, voucherId={}", orderId, vid);

        VoucherOrder dbOrder = voucherOrderMapper.selectById(orderId);
        // 读取方式必须是 hget（单个 field），不是 entries（整张 hash）——
        // entries 的返回永远非空，会把"存在"判成"不存在"（SPEC-04 §1.4）
        String detail = (String) stringRedisTemplate.opsForHash()
                .get(RedisConstants.detailKey(vid), orderId.toString());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("orderId", orderId);
        report.put("voucherId", vid);
        report.put("dbOrderExists", dbOrder != null);
        report.put("redisDetailExists", detail != null);

        // 1) 订单维度：Redis 已预扣（明细在）但 DB 无订单 → 消息丢失或消费失败
        if (dbOrder == null && detail != null) {
            report.put("consistent", false);
            report.put("reason", "Redis 存在预扣明细但数据库无订单（消息未落库）");
            log.warn("发现不一致订单: {}", report);
            return Result.fail("订单数据不一致，需要修复：" + report.get("reason"));
        }

        // 2) 库存维度：Redis 可售数必须等于「DB 余量 − 在途订单」
        Integer dbStock = readDbStock(vid);
        String redisStock = stringRedisTemplate.opsForValue().get(RedisConstants.stockKey(vid));
        report.put("dbStock", dbStock);
        report.put("redisStock", redisStock);

        if (dbStock != null && redisStock != null) {
            int expected = expectedRedisStock(dbStock, vid);
            report.put("expectedRedisStock", expected);
            if (Integer.parseInt(redisStock) != expected) {
                report.put("consistent", false);
                report.put("reason", "Redis 可售数 " + redisStock + " 与期望值 " + expected
                        + "（DB 余量 " + dbStock + " − 在途）不一致");
                log.warn("发现库存分歧: {}", report);
                return Result.fail("订单数据不一致，需要修复：" + report.get("reason"));
            }
        } else {
            report.put("consistent", false);
            report.put("reason", redisStock == null
                    ? "Redis 秒杀库存 key 缺失（需预热）" : "DB 库存读取失败");
            log.warn("库存状态未知，判为不一致: {}", report);
            return Result.fail("订单数据不一致，需要修复：" + report.get("reason"));
        }

        report.put("consistent", true);
        report.put("reason", "订单与库存均一致");
        log.info("订单一致性检查通过: orderId={}, voucherId={}", orderId, vid);
        return Result.ok(report);
    }

    // ------------------------------------------------------------------ 修复

    @Override
    public Result syncStockFromRedisToDb(Long voucherId) {
        RLock lock = redissonClient.getLock(STOCK_SYNC_LOCK + voucherId);
        try {
            if (!lock.tryLock(10, 60, TimeUnit.SECONDS)) {
                return Result.fail("获取同步锁失败，请稍后重试");
            }
            try {
                String redisStock = stringRedisTemplate.opsForValue()
                        .get(RedisConstants.stockKey(voucherId));
                if (redisStock == null) {
                    log.warn("Redis中不存在该优惠券库存: voucherId={}", voucherId);
                    return Result.fail("Redis中不存在该优惠券库存");
                }
                int target = Integer.parseInt(redisStock);

                Integer before = readDbStock(voucherId);
                if (before == null) {
                    return Result.fail("读取数据库库存失败，未执行同步");
                }
                if (before == target) {
                    return Result.ok(syncReport(voucherId, "SKIP_ALREADY_EQUAL", before, target,
                            "DB 库存已是 " + target + "，无需写入"));
                }

                // 真正写回 DB——这一步是原实现完全缺失的（SPEC-04 §1.2）
                Result write = voucherFeignClient.resetSeckillStock(voucherId, target);
                if (write == null || !Boolean.TRUE.equals(write.getSuccess())) {
                    String why = write == null ? "voucher-service 无响应" : write.getErrorMsg();
                    log.error("库存同步写入 DB 失败: voucherId={}, target={}, reason={}",
                            voucherId, target, why);
                    return Result.fail("库存同步失败: " + why);
                }

                auditMapper.insert(new SeckillConsistencyAudit()
                        .setVoucherId(voucherId)
                        .setAction("SYNC_REDIS_TO_DB")
                        .setBeforeStock(before)
                        .setAfterStock(target)
                        .setDetail("以 Redis 可售数为准回写 DB")
                        .setCreateTime(LocalDateTime.now()));
                log.warn("库存已按 Redis 回写 DB: voucherId={}, before={}, after={}",
                        voucherId, before, target);
                return Result.ok(syncReport(voucherId, "SYNC_REDIS_TO_DB", before, target,
                        "已按 Redis 可售数回写 DB"));
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
    public Result repairInconsistentData(Long voucherId) {
        RLock lock = redissonClient.getLock(REPAIR_LOCK + voucherId);
        try {
            if (!lock.tryLock(30, 120, TimeUnit.SECONDS)) {
                return Result.fail("获取修复锁失败，请稍后重试");
            }
            try {
                Integer dbStock = readDbStock(voucherId);
                if (dbStock == null) {
                    return Result.fail("读取数据库库存失败，未执行修复");
                }

                String stockKey = RedisConstants.stockKey(voucherId);
                String redisStock = stringRedisTemplate.opsForValue().get(stockKey);
                int expected = expectedRedisStock(dbStock, voucherId);

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("voucherId", voucherId);
                report.put("dbStock", dbStock);
                report.put("redisStockBefore", redisStock);
                report.put("expectedRedisStock", expected);

                String action = "NOOP";
                boolean repaired = false;
                if (redisStock == null) {
                    // key 缺失是运维态（Redis 重启/flush/过期），按 DB 重建
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(expected));
                    action = "REBUILD_STOCK_KEY";
                    repaired = true;
                } else if (Integer.parseInt(redisStock) != expected) {
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(expected));
                    action = "DB_TO_REDIS";
                    repaired = true;
                }

                report.put("repaired", repaired);
                report.put("action", action);
                report.put("redisStockAfter", repaired ? expected : redisStock);

                if (repaired) {
                    auditMapper.insert(new SeckillConsistencyAudit()
                            .setVoucherId(voucherId)
                            .setAction(action)
                            .setBeforeStock(redisStock == null ? null : Integer.parseInt(redisStock))
                            .setAfterStock(expected)
                            .setDetail("以 DB 余量扣减在途订单后回填 Redis")
                            .setCreateTime(LocalDateTime.now()));
                    log.warn("秒杀库存已修复: {}", report);
                } else {
                    log.info("秒杀库存无需修复: {}", report);
                }
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

    // ------------------------------------------------------------------ 待处理

    @Override
    public Result checkPendingOrders() {
        List<String> pendingOrders = stringRedisTemplate.opsForList()
                .range(RedisConstants.SECKILL_PENDING_KEY, 0, -1);

        if (pendingOrders == null || pendingOrders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String orderInfo : pendingOrders) {
            // 落库格式 orderId:userId:voucherId:timestamp（SeckillOrderDLQConsumer）
            String[] parts = orderInfo.split(":");
            if (parts.length < 4) {
                log.warn("待处理订单记录格式非法，跳过: {}", orderInfo);
                continue;
            }
            try {
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("orderId", Long.parseLong(parts[0]));
                order.put("userId", Long.parseLong(parts[1]));
                order.put("voucherId", Long.parseLong(parts[2]));
                order.put("timestamp", Long.parseLong(parts[3]));
                result.add(order);
            } catch (NumberFormatException e) {
                log.warn("待处理订单记录字段非法，跳过: {}", orderInfo);
            }
        }

        log.info("待处理订单数量: {}", result.size());
        return Result.ok(result);
    }

    // ------------------------------------------------------------------ 定时对账（SPEC-04 §5.6）

    /**
     * 定时一致性对账（验收 A1）。
     *
     * <p>原实现只有 {@code @Scheduled} 而无 {@code @EnableScheduling}，从未被注册、执行次数恒为 0。
     * 现在真正驱动对账：扫描活跃券 → 比对 Redis 可售数 与「DB 余量 − 在途」→ 分歧打告警，
     * 并用 Redisson 锁保证多实例下同一张券只有一个实例在算。
     */
    @Scheduled(fixedRate = 300000)
    public void scheduledConsistencyCheck() {
        log.info("开始执行定时一致性检查...");
        int divergences = 0;
        int scanned = 0;
        try {
            List<Long> voucherIds = activeSeckillVoucherIds();
            for (Long voucherId : voucherIds) {
                RLock lock = redissonClient.getLock(STOCK_SYNC_LOCK + voucherId);
                boolean locked = false;
                try {
                    locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
                    if (!locked) {
                        continue;
                    }
                    scanned++;
                    divergences += reconcileOne(voucherId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    if (locked && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }

            Long pendingCount = stringRedisTemplate.opsForList().size(RedisConstants.SECKILL_PENDING_KEY);
            if (pendingCount != null && pendingCount > 0) {
                log.warn("[对账告警] 待处理订单 {} 条（消费失败/死信），请及时处置", pendingCount);
            }
            log.info("定时一致性检查完成: 活跃券={}, 已扫描={}, 发现分歧={}",
                    voucherIds.size(), scanned, divergences);
        } catch (Exception e) {
            log.error("定时一致性检查异常", e);
        }
    }

    /** @return 发现的库存分歧数（0 或 1） */
    private int reconcileOne(Long voucherId) {
        Integer dbStock = readDbStock(voucherId);
        String redisStock = stringRedisTemplate.opsForValue().get(RedisConstants.stockKey(voucherId));
        Long redisBuyers = stringRedisTemplate.opsForSet().size(RedisConstants.orderKey(voucherId));
        Long dbSold = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, voucherId));

        if (redisStock == null) {
            log.error("[对账告警] 秒杀库存 key 缺失，需预热: voucherId={}, dbStock={}", voucherId, dbStock);
            return 1;
        }
        if (dbStock == null) {
            log.warn("[对账告警] DB 库存不可读，跳过: voucherId={}", voucherId);
            return 0;
        }

        int expected = expectedRedisStock(dbStock, voucherId);
        if (Integer.parseInt(redisStock) != expected) {
            log.error("[对账告警] 库存发散: voucherId={}, redis={}, 期望={}(DB {} − 在途), dbSold={}, redisBuyers={}",
                    voucherId, redisStock, expected, dbStock, dbSold, redisBuyers);
            return 1;
        }
        if (!Objects.equals(redisBuyers, dbSold)) {
            log.warn("[对账告警] 购买者数与订单数不符: voucherId={}, redisBuyers={}, dbSold={}",
                    voucherId, redisBuyers, dbSold);
        }
        return 0;
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 正常态库存等式：{@code Redis 可售数 = DB 余量 − 在途订单数}，在途 = Redis 购买者 − DB 订单。
     *
     * <p>直接把 Redis 拉平到 DB 会凭空多出「在途」那么多可售名额（超卖）——在途订单已经
     * 在 Redis 侧扣过库存、只是尚未落库，DB 还没有它们的扣减记录。
     */
    private int expectedRedisStock(Integer dbStock, Long voucherId) {
        Long redisBuyers = stringRedisTemplate.opsForSet().size(RedisConstants.orderKey(voucherId));
        Long dbSold = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, voucherId));
        long inFlight = (redisBuyers == null ? 0L : redisBuyers) - (dbSold == null ? 0L : dbSold);
        // 下界 0：Redis 被 flush 过会出现"购买者少于订单"，此时不能凭空补库存
        return (int) Math.max(0L, dbStock - Math.max(0L, inFlight));
    }

    private Map<String, Object> syncReport(Long voucherId, String action,
                                           Integer before, Integer after, String detail) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("voucherId", voucherId);
        report.put("action", action);
        report.put("dbStockBefore", before);
        report.put("dbStockAfter", after);
        report.put("detail", detail);
        return report;
    }

    private Integer readDbStock(Long voucherId) {
        try {
            Result r = voucherFeignClient.getSeckillStock(voucherId);
            if (r != null && Boolean.TRUE.equals(r.getSuccess()) && r.getData() != null) {
                return Integer.valueOf(String.valueOf(r.getData()));
            }
            log.warn("读取 DB 库存失败: voucherId={}, result={}",
                    voucherId, r == null ? "无响应" : r.getErrorMsg());
        } catch (Exception e) {
            log.warn("读取 DB 库存异常: voucherId={}", voucherId, e);
        }
        return null;
    }

    /**
     * 由 orderId 反查 voucherId：先看 DB 订单，再在**活跃券**的明细 hash 里找。
     *
     * <p>明细 hash 按 voucherId 分片，没有 voucherId 就无法直接定位，这是 SPEC-04 §1.4
     * 那个"key 缺后缀"缺陷的另一面。运维接口频率极低，遍历活跃券（通常个位数）可以接受。
     */
    private Long resolveVoucherId(Long orderId) {
        VoucherOrder dbOrder = voucherOrderMapper.selectById(orderId);
        if (dbOrder != null && dbOrder.getVoucherId() != null) {
            return dbOrder.getVoucherId();
        }
        String field = orderId.toString();
        for (Long voucherId : activeSeckillVoucherIds()) {
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForHash()
                    .hasKey(RedisConstants.detailKey(voucherId), field))) {
                return voucherId;
            }
        }
        return null;
    }

    private List<Long> activeSeckillVoucherIds() {
        try {
            Result r = voucherFeignClient.getActiveSeckillVoucherIds();
            if (r != null && Boolean.TRUE.equals(r.getSuccess()) && r.getData() instanceof List<?> list) {
                List<Long> ids = new ArrayList<>();
                for (Object item : list) {
                    ids.add(Long.valueOf(String.valueOf(item)));
                }
                return ids;
            }
            log.warn("读取活跃秒杀券列表失败: {}", r == null ? "无响应" : r.getErrorMsg());
        } catch (Exception e) {
            log.warn("读取活跃秒杀券列表异常", e);
        }
        return Collections.emptyList();
    }
}
