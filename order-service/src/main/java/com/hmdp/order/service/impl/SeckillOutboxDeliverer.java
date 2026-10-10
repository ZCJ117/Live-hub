package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀事件表补投器（SPEC-15 P2-1 方案 a / 形态 D1-b 的"定时补投"半边）
 *
 * <p><b>它补的是哪个洞</b>：入口侧的投递是同步的（{@code syncSend}），但"落库成功 →
 * 投递完成"之间存在崩溃窗口。窗口内挂掉的订单在 DB 里留着一条 {@code status=0} 的行，
 * 本类在后续轮次把它补投出去。消费端以 orderId 为主键幂等，重复投递不会重复建单。
 *
 * <p><b>与 P0-2 在途补偿器的分工</b>：补偿器扫的是 Redis 明细 Hash，覆盖"Lua 预扣后
 * 还没落库就崩"的窗口；本类扫的是 DB 事件表，覆盖"落库后还没投递就崩"的窗口。
 * 两者窗口不重叠，都保留。
 *
 * <p><b>退避</b>：只扫 {@code update_time} 早于 {@value #REDELIVER_AFTER_SECONDS} 秒的行。
 * 一是给入口侧的同步投递留出完成时间，避免每条正常订单都被重复投一次；
 * 二是投递失败时会刷新 {@code update_time}（列上带 {@code ON UPDATE CURRENT_TIMESTAMP}），
 * 天然形成固定间隔重试，不会对持续失败的行热循环。
 */
@Component
@Slf4j
public class SeckillOutboxDeliverer {

    /** 事件行状态：待投递（与 {@code VoucherOrderServiceImpl.OUTBOX_STATUS_PENDING} 同义） */
    private static final int STATUS_PENDING = 0;

    /** 事件行状态：已投递 */
    private static final int STATUS_DELIVERED = 1;

    /** 刚写入/刚失败的行留多久才允许补投（秒） */
    static final long REDELIVER_AFTER_SECONDS = 30L;

    /** 单轮扫描上限：一次故障积压后不至于单轮扫爆内存 */
    static final int BATCH_SIZE = 100;

    /** 单条事件行的补投次数上限；达到后停止自动补投并告警 */
    static final int MAX_RETRY = 5;

    @Resource
    private SeckillOutboxMapper seckillOutboxMapper;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillMetrics seckillMetrics;

    /**
     * 周期性补投。
     *
     * <p>fixedDelay 而非 fixedRate：本轮补投的耗时不应与下一轮重叠，
     * 否则大量积压时会有两轮同时扫同一批行（与 {@code SeckillInFlightCompensator} 同口径）。
     */
    @Scheduled(fixedDelayString = "${hmdp.seckill.outbox.deliver-interval-ms:3000}")
    public void deliverPending() {
        List<SeckillOutbox> pending;
        try {
            pending = seckillOutboxMapper.selectList(Wrappers.<SeckillOutbox>lambdaQuery()
                    .eq(SeckillOutbox::getStatus, STATUS_PENDING)
                    .lt(SeckillOutbox::getRetryCount, MAX_RETRY)
                    .le(SeckillOutbox::getUpdateTime,
                            LocalDateTime.now().minusSeconds(REDELIVER_AFTER_SECONDS))
                    .orderByAsc(SeckillOutbox::getUpdateTime)
                    // 项目未配置 MP 分页插件，沿用既有手写 LIMIT 的做法
                    .last("LIMIT " + BATCH_SIZE));
        } catch (Exception e) {
            log.error("事件表补投扫描失败", e);
            return;
        }

        if (pending.isEmpty()) {
            return;
        }

        int delivered = 0;
        int failed = 0;
        for (SeckillOutbox row : pending) {
            if (deliverOne(row)) {
                delivered++;
            } else {
                failed++;
            }
        }
        log.warn("事件表补投完成: 扫描={}, 补投成功={}, 失败={}", pending.size(), delivered, failed);
    }

    private boolean deliverOne(SeckillOutbox row) {
        boolean sent = seckillOrderProducer.sendSeckillOrderMessage(
                new SeckillOrderMessage(row.getId(), row.getUserId(), row.getVoucherId()));
        if (sent) {
            // 条件更新：入口侧可能已抢先置位，只有仍是待投递才改写
            seckillOutboxMapper.update(null, Wrappers.<SeckillOutbox>lambdaUpdate()
                    .eq(SeckillOutbox::getId, row.getId())
                    .eq(SeckillOutbox::getStatus, STATUS_PENDING)
                    .set(SeckillOutbox::getStatus, STATUS_DELIVERED));
            seckillMetrics.incrementOutboxRedelivered();
            log.warn("事件表补投成功: orderId={}, retryCount={}", row.getId(), row.getRetryCount());
            return true;
        }

        int nextRetry = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        seckillOutboxMapper.update(null, Wrappers.<SeckillOutbox>lambdaUpdate()
                .eq(SeckillOutbox::getId, row.getId())
                .setSql("retry_count = retry_count + 1"));
        seckillMetrics.incrementOutboxRedeliverFail();
        if (nextRetry >= MAX_RETRY) {
            seckillMetrics.incrementOutboxExhausted();
            log.error("[事件表补投耗尽] 该订单需人工核对: orderId={}, userId={}, voucherId={}, retryCount={}",
                    row.getId(), row.getUserId(), row.getVoucherId(), nextRetry);
        } else {
            log.warn("事件表补投失败，下轮重试: orderId={}, retryCount={}", row.getId(), nextRetry);
        }
        return false;
    }
}
