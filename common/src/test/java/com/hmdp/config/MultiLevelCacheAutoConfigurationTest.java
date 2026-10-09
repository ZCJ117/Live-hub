package com.hmdp.config;

import com.hmdp.cache.CacheInvalidationPublisher;
import com.hmdp.cache.CacheStatsLogger;
import com.hmdp.cache.LocalCacheRegistry;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.cache.MultiLevelCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 自动配置的覆盖边界：本类只验证 Bean 装配与配置绑定；
 * 真实订阅链路由 e2e 阶段② 验证（见下方用例注释）。
 */
class MultiLevelCacheAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MultiLevelCacheAutoConfiguration.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    /**
     * D7 / 设计文档 §8：未开启开关时，其他 7 个服务不新增任何 Bean。
     *
     * <p>{@link RedisMessageListenerContainer} 是「零新增 Redis 连接」红线的唯一主角：
     * 它会真的开一条订阅连接，其余组件都只是内存对象（故本负向用例不预置 mock 容器）。
     */
    @Test
    void 未开启开关时不装配任何二级缓存Bean() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(MultiLevelCacheFactory.class);
            assertThat(context).doesNotHaveBean(LocalCacheRegistry.class);
            assertThat(context).doesNotHaveBean(CacheInvalidationPublisher.class);
            assertThat(context).doesNotHaveBean(CacheStatsLogger.class);
            assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);
        });
    }

    /**
     * 开启开关后装配组件 Bean 并绑定配置默认值。
     *
     * <p>本用例预先提供一个 mock 的 {@link RedisMessageListenerContainer}：
     * 真实容器的 start() 会异步连 Redis，切片测试里没有必要（也避免重试噪音）。
     * 真实订阅链路由 e2e 阶段②验证，切片测试不覆盖它。
     */
    @Test
    void 开启开关后装配组件Bean并绑定默认配置() {
        runner.withBean(RedisMessageListenerContainer.class, () -> mock(RedisMessageListenerContainer.class))
                .withPropertyValues("hmdp.cache.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MultiLevelCacheFactory.class);
                    assertThat(context).hasSingleBean(LocalCacheRegistry.class);
                    assertThat(context).hasSingleBean(CacheInvalidationPublisher.class);
                    assertThat(context).hasSingleBean(CacheStatsLogger.class);
                    MultiLevelCacheProperties properties = context.getBean(MultiLevelCacheProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getL1MaxSize()).isEqualTo(1000);
                    assertThat(properties.getL1Ttl()).hasSeconds(10);
                });
    }
}
