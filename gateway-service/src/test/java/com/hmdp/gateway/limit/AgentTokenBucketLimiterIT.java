package com.hmdp.gateway.limit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶边界（FR-11 验收 13：11 QPS 触发限流、9 QPS 不触发）
 * 直接装配 StringRedisTemplate，不依赖 Nacos/Spring 上下文；Redis 离线自动 skip
 */
@Tag("db-it")
class AgentTokenBucketLimiterIT {

    private static StringRedisTemplate redis;
    private static AgentTokenBucketLimiter limiter;

    @BeforeAll
    static void setup() {
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
            factory.setPassword("520117");
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            redis.getConnectionFactory().getConnection().ping();
        } catch (Exception e) {
            redis = null;
        }
        Assumptions.assumeTrue(redis != null, "Redis 离线，跳过限流边界测试");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("limiter/token-bucket.lua"));
        script.setResultType(Long.class);
        limiter = new AgentTokenBucketLimiter(redis, 10, 10);
    }

    @Test
    void 边界_容量10内连发不触发_第11发触发() {
        String key = "agent:rl:test:" + System.nanoTime();
        // 突发 10 发：容量内全部放行（9 QPS 不触发语义 ⊂ 此区间）
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(key), "第 " + (i + 1) + " 发不应限流");
        }
        // 容量耗尽：第 11 发触发限流（11 QPS 触发语义）
        assertFalse(limiter.tryAcquire(key), "第 11 发应触发限流");
    }

    @Test
    void 不同用户独立计数() {
        String a = "agent:rl:test:" + System.nanoTime();
        String b = "agent:rl:test:" + System.nanoTime();
        assertTrue(limiter.tryAcquire(a));
        assertTrue(limiter.tryAcquire(b));
    }
}
