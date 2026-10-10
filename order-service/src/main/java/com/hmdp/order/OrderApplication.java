package com.hmdp.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.hmdp.order.mapper")
@EnableCaching
// SPEC-04 §5.1：SeckillConsistencyServiceImpl 的 @Scheduled 对账任务必须有它才会被注册，
// 原实现缺此注解 → 定时一致性检查执行次数恒为 0（验收 A1 必红）
@EnableScheduling
@ComponentScan("com.hmdp")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

}