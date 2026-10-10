package com.hmdp.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 池化与依赖保护配置契约（SPEC-15 P1-1 / P1-4）
 *
 * <p>为什么用测试锁住 yaml：这些参数是"补空白"类改动，没有业务行为可断言。
 * 而仓库存在一条真实风险——{@code bootstrap.yaml} 里
 * {@code spring.config.import: optional:nacos:order-service.yaml}，Nacos 上的同名
 * data-id 会**覆盖**本地 application.yaml。本地文件被删/被覆盖时若不红，等于没做。
 *
 * <p>本测试只保证"本地文件里确实写了这些键"，不保证运行时生效（那需要实测，见验证手册）。
 */
class PoolAndFeignConfigContractTest {

    private static Properties loadApplicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yaml"));
        return factory.getObject();
    }

    @Test
    void 显式声明Tomcat线程上限() {
        assertEquals("200", loadApplicationYaml().getProperty("server.tomcat.threads.max"),
                "SPEC-15 P1-1：必须显式声明 tomcat 线程上限，消除「未配置」歧义");
        assertEquals("200", loadApplicationYaml().getProperty("server.tomcat.accept-count"));
    }

    @Test
    void 显式声明Hikari连接池上限() {
        Properties p = loadApplicationYaml();
        assertEquals("20", p.getProperty("spring.datasource.hikari.maximum-pool-size"),
                "SPEC-15 P1-1：Hikari 默认 10 是秒杀链路的已知瓶颈");
        assertEquals("10", p.getProperty("spring.datasource.hikari.minimum-idle"));
        assertEquals("3000", p.getProperty("spring.datasource.hikari.connection-timeout"));
    }

    @Test
    void Redis连接池上调() {
        assertEquals("50", loadApplicationYaml().getProperty("spring.data.redis.lettuce.pool.max-active"),
                "SPEC-15 P1-1：原值 10 会在高并发下成为 Lettuce 排队点");
    }
}
