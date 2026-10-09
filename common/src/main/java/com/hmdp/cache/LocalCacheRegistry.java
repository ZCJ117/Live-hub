package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本实例 L1 缓存注册表（二级缓存设计文档 §4.3）。
 *
 * <p>订阅端收到广播后遍历注册表清理 L1：采用"全量广播 + key 不匹配时 no-op"，
 * 而不是按缓存名路由 —— 少一层路由，也少一处会漂移的契约。
 */
@Slf4j
public class LocalCacheRegistry {

    private final List<MultiLevelCache<?>> caches = new CopyOnWriteArrayList<>();

    /** 由 {@link MultiLevelCacheFactory} 创建缓存时调用 */
    void register(MultiLevelCache<?> cache) {
        caches.add(cache);
        log.info("L1 本地缓存已注册：{}", cache.name());
    }

    /**
     * 只清本地 L1：不动 Redis、不再广播（否则回环）。
     * 单个缓存异常不影响其余缓存，异常不能外逸到 Redis 监听容器。
     */
    public void evictLocal(String key) {
        for (MultiLevelCache<?> cache : caches) {
            try {
                cache.invalidateLocal(key);
            } catch (Exception e) {
                log.error("清理 L1 失败，忽略。cache={} key={}", cache.name(), key, e);
            }
        }
    }

    /** 当前已注册的 L1 缓存（供命中率日志用） */
    public List<MultiLevelCache<?>> caches() {
        return List.copyOf(caches);
    }
}
