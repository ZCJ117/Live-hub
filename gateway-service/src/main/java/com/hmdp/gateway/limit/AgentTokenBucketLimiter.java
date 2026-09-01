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

    /** @return true=放行 */
    public boolean tryAcquire(String key) {
        Long result = redisTemplate.execute(SCRIPT, List.of(key),
                String.valueOf(capacity), String.valueOf(refillPerSec),
                String.valueOf(System.currentTimeMillis() / 1000));
        boolean allowed = result != null && result == 1L;
        if (!allowed) {
            log.debug("agent 路由限流触发: key={}", key);
        }
        return allowed;
    }
}
