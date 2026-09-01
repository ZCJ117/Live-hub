package com.hmdp.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能客服 Agent 服务（模块 M5）
 * PRD：PRD-智能客服工单Agent.md v1.0
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.hmdp.agent.feign")
@EnableScheduling
@MapperScan("com.hmdp.agent.mapper")
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
