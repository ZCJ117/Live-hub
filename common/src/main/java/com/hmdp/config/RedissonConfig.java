package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置类
 * Redisson是一个基于Redis的Java驻内存数据网格（In-Memory Data Grid）和分布式锁框架
 * 提供了丰富的分布式数据结构和服务，如分布式锁、分布式集合、分布式对象等
 */
@Configuration
public class RedissonConfig {

    /**
     * Redis主机地址，从Spring配置中读取，默认值为localhost
     * 使用统一的spring.redis配置路径
     */
    @Value("${spring.redis.host:localhost}")
    private String redisHost;
    
    /**
     * Redis端口号，从Spring配置中读取，默认值为6379
     * 使用统一的spring.redis配置路径
     */
    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * 创建并配置RedissonClient实例
     * RedissonClient是Redisson的核心客户端，用于与Redis服务器交互
     * 
     * @return RedissonClient实例，可用于获取分布式锁、分布式集合等
     */
    @Bean
    public RedissonClient redissonClient() {
        // 创建Redisson配置对象
        Config config = new Config();
        
        // 配置Redis连接，此处使用单机模式
        // 可根据实际需求扩展为集群模式、哨兵模式或主从模式
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort) // Redis连接地址，格式：redis://ip:port
                // 可添加更多配置，如密码、连接池大小等
                // .setPassword("password") // Redis密码（如果有）
                // .setConnectionPoolSize(10) // 连接池大小
                // .setConnectionMinimumIdleSize(5) // 最小空闲连接数
                ;
        
        // 创建并返回RedissonClient实例
        return Redisson.create(config);
    }
}