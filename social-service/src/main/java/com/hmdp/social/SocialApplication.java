package com.hmdp.social;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 社交服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.hmdp.social.mapper")
@EnableCaching
public class SocialApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocialApplication.class, args);
    }

}
