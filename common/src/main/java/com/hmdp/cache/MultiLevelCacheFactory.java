package com.hmdp.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Type;

/**
 * 二级缓存工厂（二级缓存设计文档 §4.2）。
 *
 * <p>创建 L1 实例并登记到 {@link LocalCacheRegistry} —— 登记是广播能清到它的前提。
 */
public class MultiLevelCacheFactory {

    private final StringRedisTemplate stringRedisTemplate;
    private final CacheInvalidationPublisher publisher;
    private final LocalCacheRegistry registry;
    private final MultiLevelCacheProperties properties;

    public MultiLevelCacheFactory(StringRedisTemplate stringRedisTemplate,
                                  CacheInvalidationPublisher publisher,
                                  LocalCacheRegistry registry,
                                  MultiLevelCacheProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.publisher = publisher;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 创建（并登记）一个二级缓存。
     *
     * @param name      缓存名，仅用于日志与命中率统计
     * @param valueType 值类型，如 {@code Shop.class} 或
     *                  {@code new TypeReference<List<ShopType>>(){}.getType()}
     */
    public <V> MultiLevelCache<V> create(String name, Type valueType) {
        MultiLevelCache<V> cache =
                new MultiLevelCache<>(name, valueType, stringRedisTemplate, publisher, properties);
        registry.register(cache);
        return cache;
    }
}
