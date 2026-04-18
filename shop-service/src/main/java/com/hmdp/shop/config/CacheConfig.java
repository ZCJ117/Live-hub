package com.hmdp.shop.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 缓存配置类
 * 配置 Redis 缓存，支持 shopCache 缓存名称
 * 
 * 本配置将Redis作为二级缓存（L2 Cache）使用，配合Spring Cache注解实现多级缓存架构。
 * 一级缓存为本地缓存（如Caffeine），二级缓存为Redis分布式缓存。
 * 当本地缓存未命中时，会自动查询Redis缓存；Redis缓存未命中时，才查询数据库。
 * 
 * 注意：当前版本直接使用Redis作为缓存，未配置本地一级缓存，可根据性能需求扩展。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 配置 Redis 缓存管理器
     * 直接使用 Redis 作为缓存，避免使用 LayeredCacheManager 导致的版本兼容问题
     * 
     * 配置说明：
     * 1. 缓存过期时间：30分钟，平衡缓存新鲜度和内存使用
     * 2. 键序列化：StringRedisSerializer，保证Redis键的可读性
     * 3. 值序列化：GenericJackson2JsonRedisSerializer，支持复杂对象存储
     * 
     * 扩展建议：
     * - 可集成Caffeine作为一级本地缓存，提升读取性能
     * - 可配置不同的缓存名称使用不同的TTL策略
     * - 可添加缓存统计和监控功能
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        // 配置 Redis 缓存序列化
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30)) // Redis 缓存过期时间
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer())); // 使用默认的 JSON 序列化器

        // 创建 Redis 缓存管理器
        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
