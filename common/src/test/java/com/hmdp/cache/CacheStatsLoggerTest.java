package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CacheStatsLogger.format} 的输出契约。
 *
 * <p>前缀是 e2e 脚本 grep 的锚点（`grep 'L1 命中率统计'`），字段是运维读日志的依据——
 * 两者都是对外契约，故这里用**字面量**断言，不引用生产常量：
 * 常量漂移时本用例必须失败（本批已踩过"断言引用生产常量导致静默失守"的坑）。
 */
class CacheStatsLoggerTest {

    @Test
    void format输出前缀与字段契约() {
        // 用真实 Caffeine cache 造出确定性的 hit/miss 计数
        Cache<String, String> cache = Caffeine.newBuilder().recordStats().build();
        cache.getIfPresent("absent");   // miss
        cache.put("k", "v");
        cache.getIfPresent("k");        // hit
        CacheStats stats = cache.stats();

        String line = CacheStatsLogger.format("shop", 1L, stats);

        assertThat(line).startsWith("L1 命中率统计");
        assertThat(line).contains("cache=shop");
        assertThat(line).contains("hitRate=");
        assertThat(line).contains("hit=");
        assertThat(line).contains("miss=");
        assertThat(line).contains("size=1");
        // 计数自洽：1 命中 1 未命中 ⇒ 命中率 0.5（顺带锁住字段真的取自传入的 stats）
        assertThat(line).contains("hitRate=0.5000");
    }
}
