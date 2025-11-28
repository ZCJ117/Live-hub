package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableCaching
// NOTE 缓存配置类，配置二级缓存管理器  实现了本地缓存(Caffeine) + 远程缓存(Redis)的双层缓存架构
// NOTE 这个是一个超级实用的缓存架构，既利用了本地缓存的高速访问优势，又利用了Redis的分布式特性，适合大多数高并发应用场景
public class CacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${spring.application.name:hmdp}")
    private String appName;


    //NOTE 主配置方法
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        logger.info("初始化二级缓存管理器...");

        // NOTE 创建Redis缓存管理器 远程缓存
        RedisCacheManager redisCacheManager = RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(getRedisCacheConfigurationWithTtl(3600)) // 默认1小时过期
                .withCacheConfiguration("userCache", getRedisCacheConfigurationWithTtl(1800)) // 用户缓存30分钟
                .withCacheConfiguration("productCache", getRedisCacheConfigurationWithTtl(7200)) // 产品缓存2小时
                .withCacheConfiguration("shopCache", getRedisCacheConfigurationWithTtl(1800)) // 新增：店铺缓存30分钟
                .build();

        logger.info("Redis缓存管理器初始化完成，已配置缓存: userCache(30min), productCache(2h), shopCache(30min)");

        // 创建Caffeine缓存管理器
        // NOTE 本地缓存 缓存热点数据，减轻Redis压力 提升访问速度
        CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
        caffeineCacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(100) // 初始容量
                .maximumSize(1000) // 最大容量
                .expireAfterWrite(5, TimeUnit.MINUTES) // 写入后5分钟过期
                .recordStats()); // 开启统计 // NOTE 记录缓存命中率等统计数据，方便调优

        logger.info("Caffeine本地缓存管理器初始化完成，配置: 初始容量100, 最大容量1000, 过期时间5分钟");

        // 创建二级缓存管理器
        // NOTE 核心类，管理本地和远程缓存的读写逻辑 二级缓存管理器LayeringCacheManager
        LayeringCacheManager layeringCacheManager = new LayeringCacheManager(caffeineCacheManager, redisCacheManager);
        // NOTE 读写逻辑：先读本地缓存，未命中再读远程缓存；写入和删除操作同时作用于本地和远程缓存


        logger.info("二级缓存管理器初始化完成，采用Caffeine本地缓存 + Redis远程缓存架构");

        return layeringCacheManager;
    }


    // NOTE Redis缓存配置方法，设置序列化和TTL
    private RedisCacheConfiguration getRedisCacheConfigurationWithTtl(long seconds) {
        logger.debug("创建Redis缓存配置，TTL: {}秒", seconds);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(seconds))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> {
                    String prefix = appName + ":" + cacheName + ":";
                    logger.trace("缓存前缀: {}", prefix);
                    return prefix;
                });
    }

    // NOTE二级缓存管理器实现 核心类 管理两级缓存的读写逻辑
    public static class LayeringCacheManager implements CacheManager {

        private static final Logger logger = LoggerFactory.getLogger(LayeringCacheManager.class);

        private final CacheManager localCacheManager;
        private final CacheManager remoteCacheManager;
        private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

        public LayeringCacheManager(CacheManager localCacheManager, CacheManager remoteCacheManager) {
            this.localCacheManager = localCacheManager;
            this.remoteCacheManager = remoteCacheManager;
            logger.info("创建二级缓存管理器，本地缓存: {}, 远程缓存: {}",
                    localCacheManager.getClass().getSimpleName(),
                    remoteCacheManager.getClass().getSimpleName());
        }

        @Override
        public Cache getCache(String name) {
            logger.debug("获取缓存实例: {}", name);

            return cacheMap.computeIfAbsent(name, cacheName -> {
                Cache localCache = localCacheManager.getCache(cacheName);
                Cache remoteCache = remoteCacheManager.getCache(cacheName);

                if (localCache == null) {
                    logger.warn("本地缓存管理器未找到缓存: {}", cacheName);
                }
                if (remoteCache == null) {
                    logger.warn("远程缓存管理器未找到缓存: {}", cacheName);
                }

                LayeringCache layeringCache = new LayeringCache(localCache, remoteCache);
                logger.info("创建二级缓存实例: {}", cacheName);
                return layeringCache;
            });
        }

        @Override
        public Collection<String> getCacheNames() {
            Set<String> names = new LinkedHashSet<>();
            names.addAll(localCacheManager.getCacheNames());
            names.addAll(remoteCacheManager.getCacheNames());

            logger.debug("获取所有缓存名称，本地缓存: {}, 远程缓存: {}, 合并后: {}",
                    localCacheManager.getCacheNames().size(),
                    remoteCacheManager.getCacheNames().size(),
                    names.size());

            return names;
        }

        // 二级缓存实现
        static class LayeringCache implements Cache {

            private static final Logger logger = LoggerFactory.getLogger(LayeringCache.class);

            private final Cache localCache;
            private final Cache remoteCache;
            private final String name;

            // 新增：命中率统计计数器（原子操作，无锁开销）
            private final AtomicLong totalRequests = new AtomicLong(0);
            private final AtomicLong hitCount = new AtomicLong(0);

            public LayeringCache(Cache localCache, Cache remoteCache) {
                this.localCache = localCache;
                this.remoteCache = remoteCache;
                this.name = localCache != null ? localCache.getName() : "unknown";
                logger.debug("创建LayeringCache实例: {}", this.name);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public Object getNativeCache() {
                return this;
            }

            @Override
            public ValueWrapper get(Object key) {
                logger.debug("【二级缓存】查询 - 缓存名: {}, 键: {}", name, key);
                totalRequests.incrementAndGet(); // 请求计数

                // 先查本地缓存
                ValueWrapper wrapper = localCache.get(key);
                if (wrapper != null) {
                    logger.debug("【二级缓存】本地缓存命中 - 缓存名: {}, 键: {}", name, key);
                    hitCount.incrementAndGet(); // 命中计数
                    return wrapper;
                }

                logger.debug("【二级缓存】本地缓存未命中，查询远程缓存 - 缓存名: {}, 键: {}", name, key);
                wrapper = remoteCache.get(key);
                if (wrapper != null) {
                    logger.debug("【二级缓存】远程缓存命中，回填本地缓存 - 缓存名: {}, 键: {}", name, key);
                    Object value = wrapper.get();
                    localCache.put(key, value);
                    hitCount.incrementAndGet(); // 命中计数
                } else {
                    logger.debug("【二级缓存】远程缓存未命中 - 缓存名: {}, 键: {}", name, key);
                }

                // 每1000次请求打印一次命中率（避免频繁日志）
                if (totalRequests.get() % 1000 == 0) {
                    double hitRate = (double) hitCount.get() / totalRequests.get();
                    logger.info("【缓存命中率统计】缓存名: {}, 总请求: {}, 命中次数: {}, 命中率: {:.2%}",
                            name, totalRequests.get(), hitCount.get(), hitRate);
                    // 重置计数器（避免数值过大）
                    totalRequests.set(0);
                    hitCount.set(0);
                }
                return wrapper;
            }

            @Override
            public <T> T get(Object key, Class<T> type) {
                logger.debug("【二级缓存】查询(带类型) - 缓存名: {}, 键: {}, 类型: {}", name, key, type.getSimpleName());
                totalRequests.incrementAndGet(); // 请求计数

                T value = localCache.get(key, type);
                if (value != null) {
                    logger.debug("【二级缓存】本地缓存命中(带类型) - 缓存名: {}, 键: {}", name, key);
                    hitCount.incrementAndGet(); // 命中计数
                    return value;
                }

                logger.debug("【二级缓存】本地缓存未命中(带类型)，查询远程缓存 - 缓存名: {}, 键: {}", name, key);
                value = remoteCache.get(key, type);
                if (value != null) {
                    logger.debug("【二级缓存】远程缓存命中(带类型)，回填本地缓存 - 缓存名: {}, 键: {}", name, key);
                    localCache.put(key, value);
                    hitCount.incrementAndGet(); // 命中计数
                } else {
                    logger.debug("【二级缓存】远程缓存未命中(带类型) - 缓存名: {}, 键: {}", name, key);
                }

                // 每1000次请求打印一次命中率
                if (totalRequests.get() % 1000 == 0) {
                    double hitRate = (double) hitCount.get() / totalRequests.get();
                    logger.info("【缓存命中率统计】缓存名: {}, 总请求: {}, 命中次数: {}, 命中率: {:.2%}",
                            name, totalRequests.get(), hitCount.get(), hitRate);
                    totalRequests.set(0);
                    hitCount.set(0);
                }
                return value;
            }

            @Override
            public <T> T get(Object key, Callable<T> valueLoader) {
                logger.debug("【二级缓存】查询(加载器) - 缓存名: {}, 键: {}", name, key);
                totalRequests.incrementAndGet(); // 请求计数（不计入命中，因为可能从DB加载）

                try {
                    T value = localCache.get(key, () -> {
                        logger.debug("【二级缓存】本地缓存未命中(加载器)，查询远程缓存 - 缓存名: {}, 键: {}", name, key);
                        try {
                            return remoteCache.get(key, valueLoader);
                        } catch (Exception e) {
                            logger.warn("【二级缓存】远程缓存查询异常，执行valueLoader加载数据 - 缓存名: {}, 键: {}, 异常: {}",
                                    name, key, e.getMessage());
                            T newValue = valueLoader.call();
                            if (newValue != null) {
                                logger.debug("【二级缓存】数据加载成功，填充远程缓存 - 缓存名: {}, 键: {}", name, key);
                                remoteCache.put(key, newValue);
                            }
                            return newValue;
                        }
                    });

                    if (value != null) {
                        logger.debug("【二级缓存】查询成功(加载器) - 缓存名: {}, 键: {}", name, key);
                    } else {
                        logger.debug("【二级缓存】查询返回null(加载器) - 缓存名: {}, 键: {}", name, key);
                    }
                    return value;
                } catch (Exception e) {
                    logger.error("【二级缓存】本地缓存异常，尝试直接读远程缓存 - 缓存名: {}, 键: {}, 异常: {}",
                            name, key, e.getMessage());
                    try {
                        return remoteCache.get(key, valueLoader);
                    } catch (Exception ex) {
                        logger.error("【二级缓存】远程缓存也异常 - 缓存名: {}, 键: {}, 异常: {}",
                                name, key, ex.getMessage());
                        if (ex instanceof RuntimeException) {
                            throw (RuntimeException) ex;
                        }
                        throw new IllegalStateException(ex);
                    }
                }
            }

            @Override
            public void put(Object key, Object value) {
                logger.debug("【二级缓存】写入 - 缓存名: {}, 键: {}, 值类型: {}",
                        name, key, value != null ? value.getClass().getSimpleName() : "null");
                remoteCache.put(key, value);
                localCache.put(key, value);
            }

            @Override
            public void evict(Object key) {
                logger.debug("【二级缓存】逐出 - 缓存名: {}, 键: {}", name, key);
                remoteCache.evict(key);
                localCache.evict(key);
            }

            @Override
            public void clear() {
                logger.info("【二级缓存】清空所有 - 缓存名: {}", name);
                remoteCache.clear();
                localCache.clear();
            }
        }


    }
}