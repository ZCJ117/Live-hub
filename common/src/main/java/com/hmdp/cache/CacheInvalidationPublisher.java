package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.hmdp.utils.RedisConstants.CACHE_INVALIDATE_CHANNEL;

/**
 * 缓存失效广播的发布端（二级缓存设计文档 §4.3）。
 *
 * <p>消息体就是被失效的 Redis 全键；Redis Pub/Sub 是 at-most-once 且无重投，
 * 因此失败只记 warn —— 消息丢失由 L1 兜底 TTL 收口，不阻断业务写路径。
 */
@Slf4j
public class CacheInvalidationPublisher {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 发布一条失效消息，失败只记 warn */
    public void publish(String key) {
        try {
            stringRedisTemplate.convertAndSend(CACHE_INVALIDATE_CHANNEL, key);
        } catch (Exception e) {
            log.warn("缓存失效广播发布失败，靠 L1 兜底 TTL 收口。key={}", key, e);
        }
    }
}
