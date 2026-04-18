package com.hmdp.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.concurrent.atomic.AtomicLong;

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
    private Timer seckillLatencyTimer;
    private Counter mqSendSuccessCounter;
    private Counter mqSendFailCounter;
    private Counter mqConsumeSuccessCounter;
    private Counter mqConsumeFailCounter;

    private final AtomicLong pendingOrderCount = new AtomicLong(0);

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

        Gauge.builder("seckill.pending.orders", pendingOrderCount, AtomicLong::get)
                .description("待处理订单数量")
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

    public void updatePendingOrderCount(long count) {
        pendingOrderCount.set(count);
    }

    public void logMetricsSummary() {
        log.info("秒杀监控指标汇总 - 请求总数: {}, 成功: {}, 失败: {}, 库存不足: {}, 重复下单: {}",
                seckillRequestCounter.count(),
                seckillSuccessCounter.count(),
                seckillFailCounter.count(),
                stockInsufficientCounter.count(),
                duplicateOrderCounter.count());
    }
}
