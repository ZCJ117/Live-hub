package com.hmdp.order.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 在途订单补偿器（SPEC-14 P0-2）
 *
 * <p><b>它补的是哪个洞</b>：{@code syncSend} 成功但消费端始终未消费时，
 * {@code seckill:stock:{vid}} 已 DECR、{@code seckill:order:{vid}} 已 SADD、
 * 明细 Hash 残留一条，但 {@code tb_voucher_order} 无记录。用户看到"秒杀成功"却永无订单，
 * 且因 SISMEMBER 命中**无法再次购买**。既有对账只校验聚合等式，
 * 在途订单一进一出刚好抵消 —— 这条泄漏在现有监控下**不可见**。
 *
 * <p><b>与消费端共用同一把锁</b>（{@code lock:order:{orderId}}，见
 * {@code SeckillOrderConsumer#handleOrder}）：补偿器可能正与一次消费重试并发处理同一订单，
 * 不同锁会同时改同一份状态。复用锁是最省事且正确的互斥方式。
 *
 * <p><b>误释放防护</b>：回滚预扣前必须①持有订单锁②二次查 DB 确认无该订单。
 * 释放是**不可逆**的（INCR + SREM 会让用户重获购买资格），宁可多扫几轮也不误放。
 *
 * <p><b>释放墓碑</b>：释放会写 {@code seckill:released:{orderId}}（TTL 24h），
 * 使迟到的 MQ 消息与死信回写无法让该订单复活（SPEC-14 §7 M7）。
 */
@Component
@Slf4j
public class SeckillInFlightCompensator {

    /** 在途超时阈值（秒）：超过该时长仍未落库即视为异常（SPEC-14 P0-2 默认 120s） */
    @Value("${hmdp.seckill.compensate.timeout-seconds:120}")
    private long timeoutSeconds;

    /** 单条在途订单的最大重投次数；耗尽后安全释放预扣 */
    @Value("${hmdp.seckill.compensate.max-resend:2}")
    private int maxResend;

    private static final String COMPENSATE_LOCK = "lock:compensate:seckill";
    private static final String ORDER_LOCK_PREFIX = "lock:order:";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillMetrics seckillMetrics;

    /**
     * 周期性扫描在途明细，对超时条目重投或安全释放。
     *
     * <p>fixedDelay（而非 fixedRate）：一次扫描的耗时不应与下一次重叠，
     * 否则多轮扫描会叠加在同一条订单上。
     */
    @Scheduled(fixedDelay = 60000)
    public void compensateInFlightOrders() {
        RLock lock = redissonClient.getLock(COMPENSATE_LOCK);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 120, TimeUnit.SECONDS);
            if (!locked) {
                // 多实例互斥：另一实例正在补偿，跳过本轮即可
                return;
            }

            int scanned = 0;
            int resent = 0;
            int released = 0;
            int cleaned = 0;
            for (Long voucherId : activeSeckillVoucherIds()) {
                Map<Object, Object> details = stringRedisTemplate.opsForHash()
                        .entries(RedisConstants.detailKey(voucherId));
                for (Map.Entry<Object, Object> entry : details.entrySet()) {
                    scanned++;
                    switch (compensateOne(voucherId, entry.getKey().toString(), entry.getValue().toString())) {
                        case RESENT -> resent++;
                        case RELEASED -> released++;
                        case CLEANED -> cleaned++;
                        default -> { /* SKIPPED */ }
                    }
                }
            }
            if (scanned > 0) {
                log.info("在途补偿完成: 扫描={}, 重投={}, 释放={}, 清理={}", scanned, resent, released, cleaned);
            }
        } catch (InterruptedException e) {
            log.warn("在途补偿任务被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("在途补偿任务异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private enum Outcome { SKIPPED, RESENT, RELEASED, CLEANED }

    private Outcome compensateOne(Long voucherId, String orderIdField, String detailJson) {
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(detailJson);
        } catch (Exception e) {
            log.warn("在途明细解析失败，跳过: voucherId={}, field={}", voucherId, orderIdField);
            return Outcome.SKIPPED;
        }
        JsonNode tsNode = node.get("ts");
        if (tsNode == null || tsNode.asLong() <= 0L) {
            // 缺少 ts 无法判龄。安全侧：跳过而非按"已超时"处理——
            // 误判会直接触发释放，方向上是超卖
            log.warn("在途明细缺少 ts，跳过: voucherId={}, field={}", voucherId, orderIdField);
            return Outcome.SKIPPED;
        }
        long ageMillis = System.currentTimeMillis() - tsNode.asLong();
        if (ageMillis <= timeoutSeconds * 1000L) {
            return Outcome.SKIPPED;
        }

        long orderId = node.path("orderId").asLong();
        long userId = node.path("userId").asLong();
        int retryCount = node.path("retryCount").asInt(0);

        RLock orderLock = redissonClient.getLock(ORDER_LOCK_PREFIX + orderId);
        boolean locked = false;
        try {
            locked = orderLock.tryLock(0, 60, TimeUnit.SECONDS);
            if (!locked) {
                // 消费端正在处理该订单 —— 本轮不动它
                return Outcome.SKIPPED;
            }
            // 释放墓碑（SPEC-14 §7 M7）：非空说明该单已被「重投耗尽 → 安全释放」处理过
            boolean alreadyReleased = Boolean.TRUE.equals(
                    stringRedisTemplate.hasKey(RedisConstants.releasedKey(orderId)));
            if (voucherOrderMapper.selectById(orderId) != null) {
                // DB 已有单：只是消费端清理明细失败留下的残渣，删掉即可（不可回滚预扣！）
                if (alreadyReleased) {
                    // 已释放过、DB 却有单 —— 说明当初的释放判断有误（SPEC-14 §6 验收 5，必须恒为 0）
                    seckillMetrics.incrementCompensateWrongRelease();
                    log.error("[误释放] 已释放订单仍出现在 DB: orderId={}, voucherId={}", orderId, voucherId);
                }
                stringRedisTemplate.opsForHash().delete(RedisConstants.detailKey(voucherId), orderIdField);
                seckillMetrics.incrementCompensateCleanup();
                return Outcome.CLEANED;
            }
            if (alreadyReleased) {
                // 已释放、DB 无单：迟到消息或死信回写把明细又造了回来。
                // 只清理明细，**绝不再次回滚**——二次 INCR 会凭空多出库存（超卖）。
                stringRedisTemplate.opsForHash().delete(RedisConstants.detailKey(voucherId), orderIdField);
                seckillMetrics.incrementCompensateCleanup();
                log.warn("已释放订单的明细重新出现，仅清理不再回滚: orderId={}, voucherId={}", orderId, voucherId);
                return Outcome.CLEANED;
            }
            if (retryCount < maxResend) {
                resend(voucherId, orderId, userId, retryCount);
                return Outcome.RESENT;
            }
            return release(voucherId, orderId, userId, orderIdField);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.SKIPPED;
        } finally {
            if (locked && orderLock.isHeldByCurrentThread()) {
                orderLock.unlock();
            }
        }
    }

    /** 重投 MQ，并把明细里的 ts 刷新为当前时刻、retryCount 递增（重投窗口据此重新计时） */
    private void resend(Long voucherId, Long orderId, Long userId, int retryCount) {
        String key = RedisConstants.detailKey(voucherId);
        String field = orderId.toString();
        String updated = String.format(
                "{\"voucherId\":\"%d\",\"userId\":\"%d\",\"orderId\":\"%d\",\"ts\":\"%d\",\"retryCount\":%d}",
                voucherId, userId, orderId, System.currentTimeMillis(), retryCount + 1);
        stringRedisTemplate.opsForHash().put(key, field, updated);
        stringRedisTemplate.expire(key, Duration.ofSeconds(RedisConstants.SECKILL_DETAIL_TTL_SECONDS));

        boolean sent = seckillOrderProducer.sendSeckillOrderMessage(
                new SeckillOrderMessage(orderId, userId, voucherId));
        if (sent) {
            seckillMetrics.incrementCompensateResend();
            log.warn("在途订单已重投: orderId={}, voucherId={}, retryCount={}→{}",
                    orderId, voucherId, retryCount, retryCount + 1);
        } else {
            // 不重试：下一轮扫描会再投（retryCount 已递增，最多再投 maxResend 轮）。
            // 但必须留痕——投递失败会让该单在超时后走释放，用户被静默取消。
            seckillMetrics.incrementCompensateResendFail();
            log.error("在途订单重投发送失败: orderId={}, voucherId={}, retryCount={}",
                    orderId, voucherId, retryCount + 1);
        }
    }

    /**
     * 安全释放：回滚 Redis 预扣三件套 + 落 pending 供人工处置。
     *
     * <p>调用前 {@code compensateOne} 已持有订单锁并确认 DB 无该订单。
     * 释放前**再确认一次明细仍在**——若明细已被消费端删除，说明订单刚刚落库成功，
     * 此时回滚会直接造成超卖。
     */
    private Outcome release(Long voucherId, Long orderId, Long userId, String orderIdField) {
        Boolean detailStillThere = stringRedisTemplate.opsForHash()
                .hasKey(RedisConstants.detailKey(voucherId), orderIdField);
        if (!Boolean.TRUE.equals(detailStillThere)) {
            log.info("释放前明细已消失（消费端刚处理完），跳过: orderId={}", orderId);
            return Outcome.SKIPPED;
        }

        // 先立墓碑再回滚（SPEC-14 §7 M7）：若在回滚中途崩溃，残留明细的下一轮扫描会因墓碑
        // 只做清理，不会二次 INCR；顺序反过来则可能双倍回补库存（超卖）。
        // 取舍：恰在本行与 INCR 之间崩溃时，该单停在「墓碑在、明细在、库存未回补」，
        // 下轮按 CLEANED 只删明细 —— 方向是**少卖**（用户仍被标记、需人工处置），不会超卖。
        stringRedisTemplate.opsForValue().set(RedisConstants.releasedKey(orderId), "1",
                Duration.ofSeconds(RedisConstants.SECKILL_RELEASED_TTL_SECONDS));

        stringRedisTemplate.opsForValue().increment(RedisConstants.stockKey(voucherId));
        stringRedisTemplate.opsForSet().remove(RedisConstants.orderKey(voucherId), userId.toString());
        stringRedisTemplate.opsForHash().delete(RedisConstants.detailKey(voucherId), orderIdField);
        stringRedisTemplate.opsForList().rightPush(RedisConstants.SECKILL_PENDING_KEY,
                String.format("%d:%d:%d:%d", orderId, userId, voucherId, System.currentTimeMillis()));
        stringRedisTemplate.opsForList().trim(RedisConstants.SECKILL_PENDING_KEY,
                0, RedisConstants.SECKILL_LIST_MAX_SIZE - 1L);

        seckillMetrics.incrementCompensateRelease();
        log.error("[补偿释放] 在途订单重投耗尽，已回滚预扣并转人工处置: orderId={}, userId={}, voucherId={}",
                orderId, userId, voucherId);
        return Outcome.RELEASED;
    }

    private List<Long> activeSeckillVoucherIds() {
        try {
            Result r = voucherFeignClient.getActiveSeckillVoucherIds();
            if (r != null && Boolean.TRUE.equals(r.getSuccess()) && r.getData() instanceof List<?> list) {
                return list.stream().map(item -> Long.valueOf(String.valueOf(item))).toList();
            }
            log.warn("读取活跃秒杀券列表失败: {}", r == null ? "无响应" : r.getErrorMsg());
        } catch (Exception e) {
            log.warn("读取活跃秒杀券列表异常", e);
        }
        return Collections.emptyList();
    }
}
