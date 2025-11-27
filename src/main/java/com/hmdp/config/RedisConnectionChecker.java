package com.hmdp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class RedisConnectionChecker {

    private static final Logger logger = LoggerFactory.getLogger(RedisConnectionChecker.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void checkRedisConnection() {
        try {
            stringRedisTemplate.opsForValue().get("test");
            logger.info("Redis connection is OK");
        } catch (Exception e) {
            logger.warn("Redis connection failed, JetCache will use local cache only: {}", e.getMessage());
        }
    }
}