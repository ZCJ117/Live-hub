package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
// NOTE 这个是 Redis 的配置类，用于配置 RedisTemplate，使其支持 Java 8 时间类型的序列化和反序列化
public class RedisConfig {


    // NOTE 自定义 ObjectMapper
    //  @Primary 确保在有多个 ObjectMapper Bean 时优先使用此配置
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 注册 JavaTimeModule 来支持 Java 8 时间类型
        objectMapper.registerModule(new JavaTimeModule());
        // 禁用将日期序列化为时间戳
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }


    // NOTE 自定义 RedisTemplate配置
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);

        // 使用自定义的 ObjectMapper 创建序列化器
        // NOTE GenericJackson2JsonRedisSerializer 使用 Jackson 库进行 JSON 序列化
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper());

        // 设置序列化器
        // NOTE key 使用字符串序列化器，value 使用自定义的 JSON 序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(serializer);
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(serializer);

        // 设置默认序列化器
        // NOTE 这样可以确保在没有明确指定序列化器时使用该序列化器
        redisTemplate.setDefaultSerializer(serializer);

        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
}

// NOTE 基于这段代码，面试官可以深入考察你对 Spring Boot、Redis、序列化机制以及 Java 8 时间 API 的理解。

//NOTE 高频面试题：
// NOTE 1.为什么要自定义 RedisTemplate？默认的 RedisTemplate 有什么局限性？
// 答：默认的 RedisTemplate 使用 JdkSerializationRedisSerializer 进行序列化，
// 这会导致存储在 Redis 中的数据不可读且不兼容其他语言。
// 通过自定义 RedisTemplate 并使用 JSON 序列化，可以提高数据的可读性和跨语言兼容性。

// NOTE 2.为什么要注册 JavaTimeModule？
// 答：Java 8 引入了新的日期和时间 API（如 LocalDateTime、LocalDate 等），
// 默认的 Jackson ObjectMapper 不支持这些类型的序列化和反序列化。
// 注册 JavaTimeModule 可以让 Jackson 正确处理这些类型。

// NOTE 3.什么是序列化和反序列化？为什么在使用 Redis 时需要它们？
// 答：序列化是将对象转换为字节流的过程，反序列化是将字节流转换回对象的过程。
// 在使用 Redis 这样的键值存储时，数据需要以字节流的形式存储，因此需要序列化和反序列化机制。

