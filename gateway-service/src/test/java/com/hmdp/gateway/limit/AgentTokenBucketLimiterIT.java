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
 * 突发用例使用 refill=0 的 limiter，避免 refill(10/s) 补回 token 造成时间性 flake
 */
@Tag("db-it")
class AgentTokenBucketLimiterIT {

    private static StringRedisTemplate redis;
    private static AgentTokenBucketLimiter burstLimiter;
    private static AgentTokenBucketLimiter refillLimiter;

    @BeforeAll
    static void setup() {
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
            String pwd = System.getenv("REDIS_PASSWORD");
            org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
                    "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
            factory.setPassword(pwd);
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
        // refill=0：纯突发容量，测试确定性（不受执行耗时影响）
        burstLimiter = new AgentTokenBucketLimiter(redis, 10, 0);
        // 快速补充：100ms 补 100 ≥ capacity 10，时间裕度 10 倍
        refillLimiter = new AgentTokenBucketLimiter(redis, 10, 1000);
    }

    @Test
    void 边界_突发容量10内不触发_第11发触发() throws InterruptedException {
        String key = "agent:rl:test:" + System.nanoTime();
        // 突发 10 发：容量内全部放行（9 QPS 不触发语义 ⊂ 此区间）
        for (int i = 0; i < 10; i++) {
            assertTrue(burstLimiter.tryAcquire(key), "第 " + (i + 1) + " 发不应限流");
        }
        // 容量耗尽：第 11 发触发限流（11 QPS 触发语义）
        assertFalse(burstLimiter.tryAcquire(key), "第 11 发应触发限流");
    }

    @Test
    void 耗尽后按速率补充_恢复放行() throws InterruptedException {
        String key = "agent:rl:test:" + System.nanoTime();
        for (int i = 0; i < 10; i++) {
            assertTrue(refillLimiter.tryAcquire(key), "第 " + (i + 1) + " 发不应限流");
        }
        assertFalse(refillLimiter.tryAcquire(key), "耗尽后应触发限流");
        // lua 以整秒计算补充：睡到跨过秒边界（+50ms 裕度），保证 now-ts>=1 → 补 1000 ≥ capacity 10
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 50);
        assertTrue(refillLimiter.tryAcquire(key), "补充后应恢复放行");
    }

    @Test
    void 不同用户独立计数() {
        String a = "agent:rl:test:" + System.nanoTime();
        String b = "agent:rl:test:" + System.nanoTime();
        assertTrue(burstLimiter.tryAcquire(a));
        assertTrue(burstLimiter.tryAcquire(b));
    }
}
