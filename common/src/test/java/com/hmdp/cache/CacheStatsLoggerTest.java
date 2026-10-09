package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    /**
     * 周期为空值或 0 时不得把服务启动带崩：命中率日志只是观测手段。
     *
     * <p>两条分支都真实可达：{@code hmdp.cache.stats-log-interval:}（写空值）经 relaxed binding
     * 会绑成 null；写 {@code 0s} 会原样传进 {@code scheduleWithFixedDelay}（其要求 period &gt; 0，
     * 否则抛 IllegalArgumentException，即启动失败）。本用例是 {@code start()} 里那两行兜底的
     * 唯一防线——没有它们，这里会分别以 NPE / IllegalArgumentException 变红。
     */
    @Test
    void 周期为空值或0时退回默认周期而不抛异常() {
        MultiLevelCacheProperties blankInterval = new MultiLevelCacheProperties();
        blankInterval.setStatsLogInterval(null);
        CacheStatsLogger loggerWithBlank = new CacheStatsLogger(new LocalCacheRegistry(), blankInterval);
        assertThatCode(loggerWithBlank::start).doesNotThrowAnyException();
        loggerWithBlank.stop();

        MultiLevelCacheProperties zeroInterval = new MultiLevelCacheProperties();
        zeroInterval.setStatsLogInterval(Duration.ZERO);
        CacheStatsLogger loggerWithZero = new CacheStatsLogger(new LocalCacheRegistry(), zeroInterval);
        assertThatCode(loggerWithZero::start).doesNotThrowAnyException();
        loggerWithZero.stop();
    }
}
