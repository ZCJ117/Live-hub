package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-10 A1：时钟回拨防护（必须实测）。
 *
 * <p>回拨超过阈值必须**拒绝生成 ID**（宁失败，不产生可能重复的 ID）；
 * 回拨在阈值内必须**复用上次时间戳**，使 ID 的时间戳段不回退、ID 不重复。
 */
class RedisIdWorkerTest {

    /** 可拨动的时钟：测试用它把墙上时间往前/往后拨 */
    private static final class MutableClock extends Clock {
        private final AtomicLong epochSecond;
        private final ZoneId zone;

        MutableClock(long epochSecond, ZoneId zone) {
            this.epochSecond = new AtomicLong(epochSecond);
            this.zone = zone;
        }

        void set(long second) {
            this.epochSecond.set(second);
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) { return new MutableClock(epochSecond.get(), newZone); }
        @Override public Instant instant() { return Instant.ofEpochSecond(epochSecond.get()); }
    }

    private static final long BASE = 1_800_000_000L; // 2027-01-15 前后，晚于 BEGIN_TIMESTAMP
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private StringRedisTemplate stringRedisTemplate;
    private final AtomicLong counter = new AtomicLong();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());
    }

    private RedisIdWorker workerWith(MutableClock clock) {
        RedisIdWorker worker = new RedisIdWorker(stringRedisTemplate);
        worker.useClock(clock);
        return worker;
    }

    @Test
    void 时钟回拨超过阈值时拒绝生成ID() {
        MutableClock clock = new MutableClock(BASE, ZoneOffset.UTC);
        RedisIdWorker worker = workerWith(clock);

        assertTrue(worker.nextId("order") > 0);

        clock.set(BASE - 100); // 回拨 100 秒，远超 5 秒阈值
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> worker.nextId("order"));
        assertTrue(e.getMessage().contains("100"), "异常信息应带上回拨秒数：" + e.getMessage());
    }

    @Test
    void 时钟回拨在阈值内不产生重复ID且时间戳段不回退() {
        MutableClock clock = new MutableClock(BASE, ZoneOffset.UTC);
        RedisIdWorker worker = workerWith(clock);

        long first = worker.nextId("order");
        clock.set(BASE - 3); // 回拨 3 秒，在 5 秒阈值内
        long second = worker.nextId("order");

        long firstTimestamp = first >>> 32;
        long secondTimestamp = second >>> 32;
        assertEquals(BASE - BEGIN_TIMESTAMP, firstTimestamp);
        assertEquals(firstTimestamp, secondTimestamp, "回拨在阈值内应复用上次时间戳，时间戳段不得回退");
        assertNotEquals(first, second, "两次调用必须产生不同 ID");
    }

    @Test
    void 时间正常推进时时间戳段递增() {
        MutableClock clock = new MutableClock(BASE, ZoneOffset.UTC);
        RedisIdWorker worker = workerWith(clock);

        long first = worker.nextId("order");
        clock.set(BASE + 10);
        long second = worker.nextId("order");

        assertEquals((BASE + 10 - BEGIN_TIMESTAMP), second >>> 32);
        assertTrue((second >>> 32) > (first >>> 32));
    }

    @Test
    void 回拨后恢复前进仍可继续生成且不重复() {
        MutableClock clock = new MutableClock(BASE, ZoneOffset.UTC);
        RedisIdWorker worker = workerWith(clock);

        long before = worker.nextId("order");
        clock.set(BASE - 2);                       // 小幅回拨
        long duringBackward = worker.nextId("order");
        clock.set(BASE + 1);                       // 恢复前进（仍小于上次时间戳，但已在阈值内）
        long after = worker.nextId("order");

        assertNotEquals(before, duringBackward);
        assertNotEquals(duringBackward, after);
        assertNotEquals(before, after);
    }
}
