package com.hmdp.voucher;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 优惠券服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.hmdp.voucher.mapper")
@EnableCaching
public class VoucherApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoucherApplication.class, args);
    }

}