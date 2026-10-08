package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redisson 配置类。
 *
 * <p>SPEC-10 §5.2：配置键统一为 Spring Boot 3 的 {@code spring.data.redis.*}
 * （修复前读的是已不存在的 {@code spring.redis.host}，导致 host 恒回退为 localhost）。
 *
 * <p>SPEC-10 §5.3：本类登记在 {@code AutoConfiguration.imports} 中，因此**所有**服务都会
 * 加载它；用 {@code hmdp.redisson.enabled} 条件开关限定只有真正需要分布式锁的服务
 * （order / agent / rag）才创建连接，其余服务不创建、也不会因后续在公共代码中注入
 * {@code RedissonClient} 而启动失败。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "hmdp.redisson.enabled", havingValue = "true")
public class RedissonConfig {

    /** Redis 主机地址，与项目其余组件统一使用 spring.data.redis 路径 */
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    /** Redis 端口号 */
    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * 构造 Redisson 配置。抽为包级方法以便单测直接断言连接地址，无需真连 Redis。
     */
    Config buildConfig() {
        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);

        // 空密码必须跳过：某些 Redisson 版本会以空密码发起 AUTH 而认证失败
        if (redisPassword != null && !redisPassword.isBlank()) {
            serverConfig.setPassword(redisPassword);
        }
        return config;
    }

    /**
     * 创建并配置 RedissonClient 实例，用于分布式锁与分布式集合。
     *
     * <p>{@code @Lazy} 是必需的：单测以 {@code ApplicationContextRunner} 启动上下文并断言
     * BeanDefinition；若不延迟，容器刷新阶段就会真正调用本工厂方法去连 Redis，上下文的启动
     * 取决于 Redis 是否可达，测试无法离线运行。
     */
    @Bean
    @Lazy
    public RedissonClient redissonClient() {
        Config config = buildConfig();
        // SPEC-10 A2 的实测依据：启动日志可核验实际连接地址
        log.info("Redisson 连接地址: {}", config.useSingleServer().getAddress());
        return Redisson.create(config);
    }
}
