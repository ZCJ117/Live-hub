package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private static final int COUNT_BITS = 32;

    /** 允许的最大时钟回拨秒数；超过则拒绝生成 ID（宁失败，不产生可能重复的 ID，SPEC-10 §1.1） */
    private static final long MAX_BACKWARD_SECONDS = 5;

    private static final DateTimeFormatter DAY_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate stringRedisTemplate;

    /** 时间源。生产为系统默认时区；同包单测用 {@link #useClock} 换成可控时钟（SPEC-10 §8.1） */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * 上次使用的时间戳。同实例内由 {@link #nextId} 的 synchronized 保护；
     * 多实例场景下 count 由 Redis INCR 保证唯一，相同时间戳的实例共享同一 count 空间。
     */
    private volatile long lastTimestamp = -1L;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 仅供同包单测：替换时间源，用于构造时钟回拨场景。生产代码不调用。
     */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    public synchronized long nextId(String keyPrefix) {
        //1.生成时间差
        LocalDateTime now = LocalDateTime.now(clock);
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.时钟回拨防护（SPEC-10 §1.1）
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_BACKWARD_SECONDS) {
                throw new IllegalStateException("检测到时钟回拨 " + offset + " 秒，超过阈值 "
                        + MAX_BACKWARD_SECONDS + " 秒，拒绝生成 ID");
            }
            log.warn("检测到时钟回拨 {} 秒，复用上次时间戳 {}", offset, lastTimestamp);
        } else {
            lastTimestamp = timestamp;
        }
        timestamp = lastTimestamp;

        //3.生成序列号
        //3.1获取当前日期，精确到天
        String date = now.format(DAY_PATTERN);
        //3.2自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //4.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
