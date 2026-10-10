package com.hmdp.order.metrics;

import io.micrometer.core.instrument.Counter;
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
    private Timer seckillLatencyTimer;
    private Counter mqSendSuccessCounter;
    private Counter mqSendFailCounter;
    private Counter mqConsumeSuccessCounter;
    private Counter mqConsumeFailCounter;
    private Counter retryExhaustedCounter;
    private Counter dlqConsumedCounter;

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

}
