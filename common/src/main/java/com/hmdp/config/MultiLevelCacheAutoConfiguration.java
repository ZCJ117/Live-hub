package com.hmdp.config;

import com.hmdp.cache.CacheInvalidationListener;
import com.hmdp.cache.CacheInvalidationPublisher;
import com.hmdp.cache.CacheStatsLogger;
import com.hmdp.cache.LocalCacheRegistry;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.cache.MultiLevelCacheProperties;
import com.hmdp.utils.RedisConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 二级缓存自动配置（二级缓存设计文档 §8）。
 *
 * <p>登记方式与 {@code RedissonConfig} 一致：{@code @Configuration} + 登记进
 * {@code AutoConfiguration.imports}，因此本类会随 common 进入全部服务；
 * 用 {@code hmdp.cache.enabled} 条件开关限定只有真正需要的服务
 * （本期只有 shop-service）才创建 Bean 与 Redis 订阅连接，其余服务零影响。
 */
@Configuration
@EnableConfigurationProperties(MultiLevelCacheProperties.class)
@ConditionalOnProperty(prefix = "hmdp.cache", name = "enabled", havingValue = "true")
public class MultiLevelCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LocalCacheRegistry localCacheRegistry() {
        return new LocalCacheRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheInvalidationPublisher cacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate) {
        return new CacheInvalidationPublisher(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public MultiLevelCacheFactory multiLevelCacheFactory(StringRedisTemplate stringRedisTemplate,
                                                         CacheInvalidationPublisher publisher,
                                                         LocalCacheRegistry registry,
                                                         MultiLevelCacheProperties properties) {
        return new MultiLevelCacheFactory(stringRedisTemplate, publisher, registry, properties);
    }

    /**
     * 订阅端容器。{@code @ConditionalOnMissingBean} 在本类按**类型**匹配：应用若自建
     * {@code RedisMessageListenerContainer}，本 Bean 会退避，以免两个容器争抢同一连接工厂。
     * 退避的代价是广播订阅缺失、跨实例失效退化为只剩 L1 兜底 TTL —— 日后新增此类 Bean 时
     * 必须一并挂上 {@link CacheInvalidationListener}。当前仓内无其他该类 Bean，故不会发生。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory, LocalCacheRegistry registry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(new CacheInvalidationListener(registry),
                new ChannelTopic(RedisConstants.CACHE_INVALIDATE_CHANNEL));
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheStatsLogger cacheStatsLogger(LocalCacheRegistry registry, MultiLevelCacheProperties properties) {
        return new CacheStatsLogger(registry, properties);
    }
}
