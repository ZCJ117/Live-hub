package com.hmdp.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.cacheTtlSeconds;

/**
 * 二级缓存：L1 Caffeine（进程内）+ L2 Redis（分布式）。二级缓存设计文档 §4。
 *
 * <p>约定：
 * <ul>
 *   <li>key 直接使用 Redis 全键（如 {@code cache:shop:12}），L1 与 L2 共用键空间，
 *       从而复用 {@code RedisConstants} 的键唯一事实源与既有契约测试；</li>
 *   <li>L2 存实体本体 JSON；空值用 {@code ""} + 60 秒短 TTL 标记防穿透，且**标记不进 L1**；</li>
 *   <li>L1 命中不访问 Redis；L2 未命中才回源 loader；</li>
 *   <li>Redis 读写异常降级为「未命中」并记 warn（设计文档 §7）：缓存故障不该让业务 500。</li>
 * </ul>
 */
@Slf4j
public class MultiLevelCache<V> {

    private final String name;
    private final Type valueType;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheInvalidationPublisher publisher;
    private final Cache<String, V> l1;

    MultiLevelCache(String name, Type valueType, StringRedisTemplate stringRedisTemplate,
                    CacheInvalidationPublisher publisher, MultiLevelCacheProperties properties) {
        this.name = name;
        this.valueType = valueType;
        this.stringRedisTemplate = stringRedisTemplate;
        this.publisher = publisher;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(properties.getL1MaxSize())
                .expireAfterWrite(properties.getL1Ttl())
                .recordStats()
                .build();
    }

    /**
     * 读：L1 → L2 → loader → 回填 L2 与 L1。
     *
     * @return 值；loader 返回 null 时写空值标记并返回 null
     */
    public V get(String key, Supplier<V> loader) {
        V local = l1.getIfPresent(key);
        if (local != null) {
            return local;
        }

        String json = redisGet(key);
        if (StrUtil.isNotBlank(json)) {
            V value = decode(json, key);
            if (value != null) {
                l1.put(key, value);
                return value;
            }
            // 反序列化失败按未命中处理，落到下面的回源分支
        } else if (json != null) {
            // 命中空值标记：DB 已确认不存在，直接返回；标记不进 L1（设计文档 §5.1）
            return null;
        }

        V loaded = loader.get();
        if (loaded == null) {
            redisSet(key, "", CACHE_NULL_TTL);
            return null;
        }
        redisSet(key, JSONUtil.toJsonStr(loaded), cacheTtlSeconds());
        l1.put(key, loaded);
        return loaded;
    }

    /**
     * 写路径失效。顺序（设计文档 §6）：先删 Redis 再清 L1 —— 反过来会让并发读
     * 在两步之间 miss L1、读到尚未删除的旧值并回灌 L1，等于给脏值续命到 L1 TTL。
     */
    public void evict(String key) {
        redisDelete(key);
        l1.invalidate(key);
        try {
            publisher.publish(key);
        } catch (Exception e) {
            // 广播只是加速手段（设计文档 §7）：DB 已提交，发布异常不能把写路径变成 500
            log.warn("缓存失效广播失败（DB 已提交，靠 L1 兜底 TTL 收口）。key={}", key, e);
        }
    }

    /** 广播回调：只清本实例 L1，不动 Redis、不再广播 */
    public void invalidateLocal(String key) {
        l1.invalidate(key);
    }

    public CacheStats stats() {
        return l1.stats();
    }

    public String name() {
        return name;
    }

    /** L1 估算条数（注意 Caffeine 淘汰是异步的，见单测 U10 注释） */
    public long estimatedSize() {
        return l1.estimatedSize();
    }

    private V decode(String json, String key) {
        try {
            return JSONUtil.toBean(json, valueType, false);
        } catch (Exception e) {
            log.warn("二级缓存反序列化失败，按未命中处理并回源。key={}", key, e);
            return null;
        }
    }

    private String redisGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取 Redis 二级缓存失败，降级回源。key={}", key, e);
            return null;
        }
    }

    private void redisSet(String key, String value, long ttlSeconds) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入 Redis 二级缓存失败，忽略。key={}", key, e);
        }
    }

    private void redisDelete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除 Redis 二级缓存失败（TTL 会兜底）。key={}", key, e);
        }
    }
}
