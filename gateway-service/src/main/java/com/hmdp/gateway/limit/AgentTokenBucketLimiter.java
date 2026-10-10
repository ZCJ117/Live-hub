package com.hmdp.gateway.limit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 路由令牌桶限流器（FR-11 T4.10/R6：agent 路由独立限流，维度=loginId）
 * Redis+Lua 原子执行；容量=突发上限，refill=每秒补充（默认 10/10）
 */
@Component
@Slf4j
public class AgentTokenBucketLimiter {

    private static final DefaultRedisScript<Long> SCRIPT;
    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("limiter/token-bucket.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;
    private final int capacity;
    private final int refillPerSec;

    public AgentTokenBucketLimiter(StringRedisTemplate redisTemplate,
                                   @Value("${agent.rate-limit.capacity:10}") int capacity,
                                   @Value("${agent.rate-limit.refill-per-sec:10}") int refillPerSec) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
    }

    /**
     * 按规则参数做放行判定（SPEC-14 P0-1 / §7 M1）。
     *
     * <p>原实现把容量与速率固定为构造期 {@code @Value}，一个 bean 只能服务一套参数；
     * 而规则表里 agent=10/10、秒杀 loginId=5/1、秒杀 ip=20/5 是三套不同参数。
     * {@code token-bucket.lua} 本就以 ARGV 接收这两项，故只需把它们从构造期移到调用期。
     *
     * @return true=放行
     */
    public boolean tryAcquire(String key, int capacity, int refillPerSec) {
        Long result;
        try {
            result = redisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(capacity), String.valueOf(refillPerSec),
                    String.valueOf(System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            // Redis 抖动时限流放行（fail-open）：短暂失去限流精度好过整条路径 500。
            // 秒杀入口同样适用——限流组件故障不得阻断下单（SPEC-14 §6 验收 7）
            log.warn("限流器 Redis 异常，fail-open 放行: key={}", key, e);
            return true;
        }
        boolean allowed = result != null && result == 1L;
        if (!allowed) {
            log.debug("限流触发: key={}", key);
        }
        return allowed;
    }

    /** 使用构造期配置的容量与速率（默认 10/10），保持既有调用点行为不变 */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, capacity, refillPerSec);
    }
}
