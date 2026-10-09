package com.hmdp.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 二级缓存配置（二级缓存设计文档 §8）。
 *
 * <p>默认关闭：本组件随 common 进入全部服务，只有显式声明
 * {@code hmdp.cache.enabled=true} 的服务才装配相关 Bean 与 Redis 订阅连接。
 */
@ConfigurationProperties(prefix = "hmdp.cache")
public class MultiLevelCacheProperties {

    /** 是否启用二级缓存 */
    private boolean enabled = false;

    /** L1 容量上限（条） */
    private long l1MaxSize = 1000;

    /** L1 兜底 TTL：广播丢失/订阅断线时的最大脏读窗口 */
    private Duration l1Ttl = Duration.ofSeconds(10);

    /** L1 命中率日志输出间隔 */
    private Duration statsLogInterval = Duration.ofSeconds(60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(long l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }

    public Duration getL1Ttl() {
        return l1Ttl;
    }

    public void setL1Ttl(Duration l1Ttl) {
        this.l1Ttl = l1Ttl;
    }

    public Duration getStatsLogInterval() {
        return statsLogInterval;
    }

    public void setStatsLogInterval(Duration statsLogInterval) {
        this.statsLogInterval = statsLogInterval;
    }
}
