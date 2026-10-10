package com.hmdp.order.metrics;

import com.hmdp.utils.RedisConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

@Component
@Slf4j
public class SeckillMetrics {

    @Resource
    private MeterRegistry meterRegistry;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private Counter seckillRequestCounter;
    private Counter seckillSuccessCounter;
    private Counter seckillFailCounter;
    private Counter stockInsufficientCounter;
    private Counter duplicateOrderCounter;
    private Counter redisStockMissingCounter;
    private Counter seckillNotStartedCounter;
    private Counter seckillEndedCounter;
    private Timer seckillLatencyTimer;
    private Counter mqSendSuccessCounter;
    private Counter mqSendFailCounter;
    private Counter mqConsumeSuccessCounter;
    private Counter mqConsumeFailCounter;
    private Counter retryExhaustedCounter;
    private Counter dlqConsumedCounter;
    private Counter compensateResendCounter;
    private Counter compensateCleanupCounter;
    private Counter compensateReleaseCounter;
    private Counter compensateWrongReleaseCounter;

    @PostConstruct
    public void init() {
        seckillRequestCounter = Counter.builder("seckill.request.total")
                .description("秒杀请求总数")
                .tag("type", "request")
                .register(meterRegistry);

        seckillSuccessCounter = Counter.builder("seckill.success.total")
                .description("秒杀成功总数")
                .tag("type", "success")
                .register(meterRegistry);

        seckillFailCounter = Counter.builder("seckill.fail.total")
                .description("秒杀失败总数")
                .tag("type", "fail")
                .register(meterRegistry);

        stockInsufficientCounter = Counter.builder("seckill.stock.insufficient")
                .description("库存不足次数")
                .tag("reason", "stock_insufficient")
                .register(meterRegistry);

        duplicateOrderCounter = Counter.builder("seckill.duplicate.order")
                .description("重复下单次数")
                .tag("reason", "duplicate_order")
                .register(meterRegistry);

        redisStockMissingCounter = Counter.builder("seckill.stock.key.missing")
                .description("Redis 库存key缺失次数（需预热，与库存不足区分）")
                .tag("reason", "redis_stock_key_missing")
                .register(meterRegistry);

        seckillNotStartedCounter = Counter.builder("seckill.window.not.started")
                .description("活动未开始被拒次数（SPEC-14 P0-3）")
                .tag("reason", "window_not_started")
                .register(meterRegistry);

        seckillEndedCounter = Counter.builder("seckill.window.ended")
                .description("活动已结束被拒次数（SPEC-14 P0-3）")
                .tag("reason", "window_ended")
                .register(meterRegistry);

        seckillLatencyTimer = Timer.builder("seckill.latency")
                .description("秒杀请求延迟")
                .register(meterRegistry);

        mqSendSuccessCounter = Counter.builder("seckill.mq.send.success")
                .description("MQ发送成功数")
                .tag("type", "mq_send")
                .register(meterRegistry);

        mqSendFailCounter = Counter.builder("seckill.mq.send.fail")
                .description("MQ发送失败数")
                .tag("type", "mq_send")
                .register(meterRegistry);

        mqConsumeSuccessCounter = Counter.builder("seckill.mq.consume.success")
                .description("MQ消费成功数")
                .tag("type", "mq_consume")
                .register(meterRegistry);

        mqConsumeFailCounter = Counter.builder("seckill.mq.consume.fail")
                .description("MQ消费失败数")
                .tag("type", "mq_consume")
                .register(meterRegistry);

        retryExhaustedCounter = Counter.builder("seckill.mq.retry.exhausted")
                .description("消费重试已达上限数（SPEC-08 §5.5：原实现用业务字段判断，恒为 0、永不触发）")
                .tag("type", "retry_exhausted")
                .register(meterRegistry);

        dlqConsumedCounter = Counter.builder("seckill.dlq.consumed")
                .description("死信队列消费数（SPEC-04 §5.3：原实现监听一个永远无投递的 topic）")
                .tag("type", "dlq")
                .register(meterRegistry);

        compensateResendCounter = Counter.builder("seckill.compensate.resend")
                .description("在途订单自动重投次数（SPEC-14 P0-2）")
                .tag("reason", "compensate_resend")
                .register(meterRegistry);

        compensateCleanupCounter = Counter.builder("seckill.compensate.cleanup")
                .description("在途明细清理次数（DB 已有单，仅删明细）")
                .tag("reason", "compensate_cleanup")
                .register(meterRegistry);

        compensateReleaseCounter = Counter.builder("seckill.compensate.release")
                .description("重投耗尽后的安全释放次数（回滚预扣）")
                .tag("reason", "compensate_release")
                .register(meterRegistry);

        compensateWrongReleaseCounter = Counter.builder("seckill.compensate.wrong.release")
                .description("误释放次数（释放后 DB 又出现该单）——必须恒为 0（SPEC-14 §6 验收 5）")
                .tag("reason", "compensate_wrong_release")
                .register(meterRegistry);

        // SPEC-14 P0-2 B4：待处置队列长度。它是「秒杀成功但无订单」泄漏的**唯一对外信号**
        // ——既有对账等式会被在途订单一进一出抵消，看不见这条泄漏。
        // 稳态应为 0；非 0 意味着有订单重投耗尽被安全释放，需要人工核对。
        Gauge.builder("seckill.pending.size", stringRedisTemplate, t -> {
                    Long size = t.opsForList().size(RedisConstants.SECKILL_PENDING_KEY);
                    return size == null ? 0L : size;
                })
                .description("待人工处置的秒杀订单数（重投耗尽后安全释放；稳态应为 0）")
                .tag("type", "pending")
                .register(meterRegistry);

        log.info("秒杀监控指标初始化完成");
    }

    public void incrementSeckillRequest() {
        seckillRequestCounter.increment();
    }

    public void incrementSeckillSuccess() {
        seckillSuccessCounter.increment();
    }

    public void incrementSeckillFail() {
        seckillFailCounter.increment();
    }

    public void incrementStockInsufficient() {
        stockInsufficientCounter.increment();
    }

    public void incrementDuplicateOrder() {
        duplicateOrderCounter.increment();
    }

    public void incrementRedisStockMissing() {
        redisStockMissingCounter.increment();
    }

    public void incrementSeckillNotStarted() {
        seckillNotStartedCounter.increment();
    }

    public void incrementSeckillEnded() {
        seckillEndedCounter.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordLatency(Timer.Sample sample) {
        sample.stop(seckillLatencyTimer);
    }

    public void incrementMqSendSuccess() {
        mqSendSuccessCounter.increment();
    }

    public void incrementMqSendFail() {
        mqSendFailCounter.increment();
    }

    public void incrementMqConsumeSuccess() {
        mqConsumeSuccessCounter.increment();
    }

    public void incrementMqConsumeFail() {
        mqConsumeFailCounter.increment();
    }

    public void incrementRetryExhausted() {
        retryExhaustedCounter.increment();
    }

    public void incrementDlqConsumed() {
        dlqConsumedCounter.increment();
    }

    public void incrementCompensateResend() {
        compensateResendCounter.increment();
    }

    public void incrementCompensateCleanup() {
        compensateCleanupCounter.increment();
    }

    public void incrementCompensateRelease() {
        compensateReleaseCounter.increment();
    }

    /** 误释放：释放决策做出后 DB 又出现该 orderId。任何一次都说明释放前置条件判断有漏洞 */
    public void incrementCompensateWrongRelease() {
        compensateWrongReleaseCounter.increment();
    }

}
