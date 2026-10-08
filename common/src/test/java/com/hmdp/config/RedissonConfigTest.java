package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-10 A2 / A3。
 *
 * <p>A2：Redis 地址必须取自项目实际使用的 {@code spring.data.redis.*} 配置路径
 * （修复前读的是不存在的 {@code spring.redis.host}，host 恒回退为 localhost）。
 *
 * <p>A3：{@code RedissonConfig} 只有显式声明 {@code hmdp.redisson.enabled=true} 的服务才装配
 * {@code RedissonClient}。本用例断言的是 **BeanDefinition**（不实例化），
 * 因此不会真的去连 Redis，离线可跑。
 */
class RedissonConfigTest {

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedissonConfig.class))
                .withPropertyValues(properties);
    }

    @Test
    void 未声明开关时不注册RedissonClient() {
        runner("spring.data.redis.host=localhost", "spring.data.redis.port=6379")
                .run(ctx -> assertThat(ctx.getBeanFactory().containsBeanDefinition("redissonClient")).isFalse());
    }

    @Test
    void 开关为false时不注册RedissonClient() {
        runner("hmdp.redisson.enabled=false")
                .run(ctx -> assertThat(ctx.getBeanFactory().containsBeanDefinition("redissonClient")).isFalse());
    }

    @Test
    void 开关为true时注册RedissonClient() {
        runner("hmdp.redisson.enabled=true")
                .run(ctx -> assertThat(ctx.getBeanFactory().containsBeanDefinition("redissonClient")).isTrue());
    }

    @Test
    void 连接地址取自spring_data_redis配置路径() {
        RedissonConfig config = new RedissonConfig();
        ReflectionTestUtils.setField(config, "redisHost", "127.0.0.2");
        ReflectionTestUtils.setField(config, "redisPort", 6390);
        ReflectionTestUtils.setField(config, "redisPassword", "");

        assertThat(config.buildConfig().useSingleServer().getAddress())
                .isEqualTo("redis://127.0.0.2:6390");
    }

    @Test
    void 密码为空串时不写入密码() {
        RedissonConfig config = new RedissonConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisPassword", "  ");

        assertThat(config.buildConfig().useSingleServer().getPassword()).isNull();
    }

    @Test
    void 密码非空时写入密码() {
        RedissonConfig config = new RedissonConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisPassword", "s3cret");

        assertThat(config.buildConfig().useSingleServer().getPassword()).isEqualTo("s3cret");
    }
}
