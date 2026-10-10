package com.hmdp.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存重建锁（SPEC-15 P1-2 §2.2）
 *
 * <p>关键边界是 fail-open：Redis 抖动时不能把"拿不到锁"变成"回源失败"，
 * 那会把一次缓存故障放大成接口 500。此分支无运行时可见行为，必须单测锁定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCacheRebuildLockTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RedisCacheRebuildLock lock;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lock = new RedisCacheRebuildLock(redisTemplate);
    }

    @Test
    void SETNX成功即抢到锁_键带统一前缀() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertTrue(lock.tryLock("cache:shop:1"));

        verify(valueOperations).setIfAbsent(eq("lock:cache:rebuild:cache:shop:1"),
                anyString(), eq(RedisCacheRebuildLock.LOCK_TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    @Test
    void SETNX失败表示他人持锁() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertFalse(lock.tryLock("cache:shop:1"));
    }

    @Test
    void Redis异常时fail_open放行回源() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertTrue(lock.tryLock("cache:shop:1"),
                "Redis 抖动时必须 fail-open：退化为无锁回源（改动前的行为），而不是让回源失败");
    }

    @Test
    void 释放走Lua脚本_只删自己持有的锁() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        lock.tryLock("cache:shop:1");

        lock.unlock("cache:shop:1");

        // 必须用比对脚本而不是裸 DEL：否则会把锁 TTL 到期后他人重新抢到的锁删掉
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void 未持锁时释放是空操作() {
        lock.unlock("cache:shop:1");

        verifyNoInteractions(redisTemplate);
    }
}
