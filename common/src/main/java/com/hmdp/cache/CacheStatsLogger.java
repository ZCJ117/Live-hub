package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * L1 命中率观测（二级缓存设计文档 §4.3）。
 *
 * <p>用守护线程定时打一行 stats，沿用 {@code ShopCacheWarmUp} 的既有范式：
 * 不占启动关键路径、失败不影响业务。
 */
@Slf4j
public class CacheStatsLogger {

    private final LocalCacheRegistry registry;
    private final MultiLevelCacheProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "multi-level-cache-stats");
        t.setDaemon(true);
        return t;
    });

    public CacheStatsLogger(LocalCacheRegistry registry, MultiLevelCacheProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        Duration interval = properties.getStatsLogInterval();
        // 配置项写空值时 relaxed binding 会绑成 null；命中率日志只是观测手段，
        // 不能因为一个空配置把服务带崩，这里退回默认周期（60 秒，与字段默认值一致）。
        long seconds = interval == null ? 60L : Math.max(1L, interval.toSeconds());
        scheduler.scheduleWithFixedDelay(this::logStats, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    void logStats() {
        for (MultiLevelCache<?> cache : registry.caches()) {
            log.info(format(cache.name(), cache.estimatedSize(), cache.stats()));
        }
    }

    static String format(String name, long size, CacheStats stats) {
        return String.format("L1 命中率统计 cache=%s size=%d hitRate=%.4f hit=%d miss=%d evict=%d",
                name, size, stats.hitRate(), stats.hitCount(), stats.missCount(), stats.evictionCount());
    }
}
