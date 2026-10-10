package com.hmdp.order.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Feign 失败率观测（SPEC-15 P1-4 §2.4 第 3 点）
 *
 * <p><b>它补的是哪个洞</b>：秒杀消费端对 {@code voucher-service} 的每次调用都带
 * 5s(readTimeout) 兜底，下游劣化时线程被逐个占满、吞吐崩塌，但**没有任何信号**
 * 能把"下游变慢"与"下游正常但重试变多"区分开。本类用最近 {@value #WINDOW_SIZE} 次
 * 调用的失败率做滑动窗口，跨过阈值时打一次 WARN 并落到指标。
 *
 * <p><b>为什么不用 Redisson / Sentinel</b>：SPEC-15 §3 D1 明确选 B（不引入 Sentinel），
 * 显式超时 + 既有 try/catch + 指标足以构成失败隔离。本类不含任何熔断动作，
 * 只做观测——真正的失败隔离由 `maxReconsumeTimes=3` + DLQ 承担。
 *
 * <p><b>告警节流</b>：进入告警态后不再重复告警，直到出现一次成功（{@code armed} 重新置位）。
 * 否则下游持续宕机的每一轮重试都会刷一条 WARN，日志反而失去信号价值。
 */
@Component
@Slf4j
public class FeignFailureRateMonitor {

    /** 滑动窗口大小：只看最近 N 次调用的结果 */
    static final int WINDOW_SIZE = 20;

    /** 触发告警所需的最小样本数：样本太少时失败率没有统计意义 */
    static final int MIN_SAMPLES = 10;

    /** 失败率告警阈值 */
    static final double FAILURE_RATE_THRESHOLD = 0.5d;

    private final SeckillMetrics seckillMetrics;

    private final Deque<Boolean> window = new ArrayDeque<>(WINDOW_SIZE);

    /** 是否处于「可以告警」状态：初始为真，告警后置假，出现一次成功后重新置真 */
    private boolean armed = true;

    /** 是否已进入告警态（仅用于测试断言） */
    private boolean alerted = false;

    public FeignFailureRateMonitor(SeckillMetrics seckillMetrics) {
        this.seckillMetrics = seckillMetrics;
    }

    /**
     * 记录一次 Feign 调用结果。
     *
     * <p>{@code synchronized}：消费端是多线程的，窗口与告警态必须作为一个整体读写。
     * 竞争极低（每单两次），不构成瓶颈。
     */
    public synchronized void record(boolean success) {
        if (success) {
            seckillMetrics.incrementFeignCallSuccess();
            armed = true;
            alerted = false;
        } else {
            seckillMetrics.incrementFeignCallFail();
        }

        if (window.size() == WINDOW_SIZE) {
            window.removeFirst();
        }
        window.addLast(success);

        if (success || !armed || window.size() < MIN_SAMPLES) {
            return;
        }

        long failures = window.stream().filter(ok -> !ok).count();
        double rate = (double) failures / window.size();
        if (rate >= FAILURE_RATE_THRESHOLD) {
            armed = false;
            alerted = true;
            log.warn("Feign 失败率超阈值: {}/{} = {}%（阈值 {}%）——voucher-service 可能已劣化，"
                            + "消费端将快速失败而非线性堆积。明细指标见 seckill.feign.call",
                    failures, window.size(), Math.round(rate * 100),
                    Math.round(FAILURE_RATE_THRESHOLD * 100));
        }
    }

    /** 当前是否处于告警态（测试与排障用） */
    public synchronized boolean isAlerted() {
        return alerted;
    }

    /** 是否仍可再次告警（测试用） */
    public synchronized boolean isArmed() {
        return armed;
    }
}
