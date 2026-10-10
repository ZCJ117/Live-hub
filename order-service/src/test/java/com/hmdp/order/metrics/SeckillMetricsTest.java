package com.hmdp.order.metrics;

import com.hmdp.utils.RedisConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 秒杀指标（SPEC-14 P0-2 / B4）
 *
 * <p>pending 列表的语义在 B3 后收敛为「需人工处置的订单」：死信不再写它，
 * 只有补偿器「重投耗尽 → 安全释放」才写。因此它的长度是**运维必须盯住的信号**，
 * 稳态应为 0；非 0 即代表有订单需要人介入。
 */
class SeckillMetricsTest {

    private SimpleMeterRegistry registry;
    private StringRedisTemplate stringRedisTemplate;
    private ListOperations<String, String> listOperations;
    private SeckillMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> ops = mock(ListOperations.class);
        listOperations = ops;
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        metrics = new SeckillMetrics();
        ReflectionTestUtils.setField(metrics, "meterRegistry", registry);
        ReflectionTestUtils.setField(metrics, "stringRedisTemplate", stringRedisTemplate);
        metrics.init();
    }

    @Test
    void pending长度指标存在且稳态为0() {
        when(listOperations.size(RedisConstants.SECKILL_PENDING_KEY)).thenReturn(0L);

        assertEquals(0.0, registry.get("seckill.pending.size").gauge().value(), 0.001);
    }

    @Test
    void pending非0时指标反映真实长度() {
        when(listOperations.size(RedisConstants.SECKILL_PENDING_KEY)).thenReturn(3L);

        assertEquals(3.0, registry.get("seckill.pending.size").gauge().value(), 0.001);
    }

    @Test
    void pending读取返回null时按0处理不抛NPE() {
        when(listOperations.size(RedisConstants.SECKILL_PENDING_KEY)).thenReturn(null);

        assertEquals(0.0, registry.get("seckill.pending.size").gauge().value(), 0.001);
    }

    @Test
    void 新增的窗口与补偿指标均可读() {
        metrics.incrementSeckillNotStarted();
        metrics.incrementSeckillEnded();
        metrics.incrementCompensateResend();
        metrics.incrementCompensateCleanup();
        metrics.incrementCompensateRelease();

        assertEquals(1.0, registry.get("seckill.window.not.started").counter().count(), 0.001);
        assertEquals(1.0, registry.get("seckill.window.ended").counter().count(), 0.001);
        assertEquals(1.0, registry.get("seckill.compensate.resend").counter().count(), 0.001);
        assertEquals(1.0, registry.get("seckill.compensate.cleanup").counter().count(), 0.001);
        assertEquals(1.0, registry.get("seckill.compensate.release").counter().count(), 0.001);
    }

    @Test
    void 释放墓碑相关的新增指标已注册() {
        metrics.incrementCompensateResendFail();
        metrics.incrementMqConsumeReleased();

        assertEquals(1.0, registry.get("seckill.compensate.resend.fail").counter().count(), 0.001);
        assertEquals(1.0, registry.get("seckill.mq.consume.released").counter().count(), 0.001);
    }
}
