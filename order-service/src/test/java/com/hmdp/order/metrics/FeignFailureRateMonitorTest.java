package com.hmdp.order.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Feign 失败率观测（SPEC-15 P1-4）
 *
 * <p>为什么必须单测：告警是纯副作用，没有返回值可断言；一旦阈值判断被写反，
 * 运行时唯一的表现在日志里，回归全绿也不会红 —— 等于不设防。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeignFailureRateMonitorTest {

    @Mock private SeckillMetrics seckillMetrics;

    private FeignFailureRateMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new FeignFailureRateMonitor(seckillMetrics);
    }

    @Test
    void 每次结果都计数到指标() {
        monitor.record(true);
        monitor.record(false);

        verify(seckillMetrics).incrementFeignCallSuccess();
        verify(seckillMetrics).incrementFeignCallFail();
    }

    @Test
    void 样本不足时不告警() {
        // 9 次失败 < MIN_SAMPLES(10)：失败率再高也不该告警
        for (int i = 0; i < 9; i++) {
            monitor.record(false);
        }

        assertFalse(monitor.isAlerted(), "样本不足时必须保持未告警");
    }

    @Test
    void 失败率达到阈值时告警() {
        for (int i = 0; i < 10; i++) {
            monitor.record(false);
        }

        assertTrue(monitor.isAlerted(), "10/10 失败率 100% ≥ 50%，必须告警");
    }

    @Test
    void 一次成功之后重新武装_持续故障会再次告警() {
        for (int i = 0; i < 10; i++) {
            monitor.record(false);
        }
        assertTrue(monitor.isAlerted());
        assertFalse(monitor.isArmed(), "告警后必须解除武装，防止刷屏");

        // 恢复：一次成功应重新武装，从而下一轮持续故障能再次告警（否则一次告警后就永久静音）
        monitor.record(true);
        assertFalse(monitor.isAlerted());
        assertTrue(monitor.isArmed());
    }

    @Test
    void 告警后同一窗口内不重复告警() {
        for (int i = 0; i < 10; i++) {
            monitor.record(false);
        }
        assertTrue(monitor.isAlerted());

        // 再失败 5 次：窗口仍是满的失败，但不该再次进入告警态（只是保持告警中）
        for (int i = 0; i < 5; i++) {
            monitor.record(false);
        }

        assertFalse(monitor.isArmed(), "未出现成功前不得重新武装，否则会刷屏");
    }

    @Test
    void 失败率低于阈值时不告警() {
        // 窗口内 20 次：6 失败 / 14 成功 = 30% < 50%
        for (int i = 0; i < 14; i++) {
            monitor.record(true);
        }
        for (int i = 0; i < 6; i++) {
            monitor.record(false);
        }

        assertFalse(monitor.isAlerted(), "失败率 30% 未达阈值，不得告警");
    }
}
