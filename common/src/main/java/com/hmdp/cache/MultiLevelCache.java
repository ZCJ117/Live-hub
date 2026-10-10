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
    private final CacheRebuildLock rebuildLock;
    private final Cache<String, V> l1;

    /** 抢锁失败后的重读间隔（毫秒） */
    private static final long LOCK_WAIT_STEP_MILLIS = 50L;

    /** 抢锁失败后的最长等待（毫秒）：超时即降级为无锁回源 */
    private static final long MAX_LOCK_WAIT_MILLIS = 500L;

    MultiLevelCache(String name, Type valueType, StringRedisTemplate stringRedisTemplate,
                    CacheInvalidationPublisher publisher, CacheRebuildLock rebuildLock,
                    MultiLevelCacheProperties properties) {
        this.name = name;
        this.valueType = valueType;
        this.stringRedisTemplate = stringRedisTemplate;
        this.publisher = publisher;
        this.rebuildLock = rebuildLock;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(properties.getL1MaxSize())
                .expireAfterWrite(properties.getL1Ttl())
                .recordStats()
                .build();
    }

    /**
     * 读：L1 → L2 → 【互斥】loader → 回填 L2 与 L1。
     *
     * <p><b>互斥的目的（SPEC-15 P1-2）</b>：L2 未命中时若所有线程一起回源，
     * 一个热点 key 过期瞬间就能把 DB 连接打满（缓存击穿）。同一 key 的并发回源
     * 收敛为 1 次。
     *
     * <p><b>两条降级路径</b>（都不改变"读不到就回源"的可用性）：
     * 抢不到锁 → 短暂重读 L2；等满 {@value #MAX_LOCK_WAIT_MILLIS}ms 仍未命中 → 无锁回源。
     * 后者覆盖"持锁者崩溃/回源极慢"的场景，宁可多打一次 DB 也不让接口失败。
     *
     * @return 值；loader 返回 null 时写空值标记并返回 null
     */
    public V get(String key, Supplier<V> loader) {
        V local = l1.getIfPresent(key);
        if (local != null) {
            return local;
        }

        L2Result<V> fromL2 = readL2(key);
        if (fromL2.present()) {
            return fromL2.value();
        }

        String rebuildToken = rebuildLock.tryLock(key);
        if (rebuildToken != null) {
            try {
                // 双检：等锁期间可能已有其它线程/实例完成了重建（含写入空值标记）
                L2Result<V> doubleChecked = readL2(key);
                if (doubleChecked.present()) {
                    return doubleChecked.value();
                }
                return loadFromSource(key, loader);
            } finally {
                // 令牌必须原样回传：锁实现靠它做"只删自己的锁"的比对
                rebuildLock.unlock(key, rebuildToken);
            }
        }

        for (long waited = 0; waited < MAX_LOCK_WAIT_MILLIS; waited += LOCK_WAIT_STEP_MILLIS) {
            sleepQuietly(LOCK_WAIT_STEP_MILLIS);
            L2Result<V> retried = readL2(key);
            if (retried.present()) {
                return retried.value();
            }
        }

        log.warn("缓存重建锁等待超时，降级为无锁回源。key={}", key);
        return loadFromSource(key, loader);
    }

    /** L2 读取结论：{@code present=true} 表示 L2 已有结论（命中空值标记时 {@code value=null}） */
    private record L2Result<V>(boolean present, V value) {
    }

    private L2Result<V> readL2(String key) {
        String json = redisGet(key);
        if (StrUtil.isNotBlank(json)) {
            V value = decode(json, key);
            if (value != null) {
                l1.put(key, value);
                return new L2Result<>(true, value);
            }
            // 反序列化失败：按未命中处理，落到回源分支
            return new L2Result<>(false, null);
        }
        if (json != null) {
            // 命中空值标记：DB 已确认不存在，直接返回；标记不进 L1（设计文档 §5.1）
            return new L2Result<>(true, null);
        }
        return new L2Result<>(false, null);
    }

    private V loadFromSource(String key, Supplier<V> loader) {
        V loaded = loader.get();
        if (loaded == null) {
            redisSet(key, "", CACHE_NULL_TTL);
            return null;
        }
        redisSet(key, JSONUtil.toJsonStr(loaded), cacheTtlSeconds());
        l1.put(key, loaded);
        return loaded;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
