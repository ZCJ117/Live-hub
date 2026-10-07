package com.hmdp.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 内部调用密钥注入（SPEC-06 §5.2 / SPEC-07 §5.2）
 *
 * <p>用户态 Feign 调用走 {@link FeignTokenRelayConfig} 透传 Authorization；
 * 内部后台调用（扣库存、检索）走本配置的共享密钥——两者显式区分。
 */
@Configuration
public class InternalTokenFeignConfig {

    @Bean
    public RequestInterceptor internalTokenRelayInterceptor(
            @Value("${hmdp.internal-token:}") String internalToken) {
        return template -> {
            if (internalToken != null && !internalToken.isBlank()) {
                template.header("X-Internal-Token", internalToken);
            }
        };
    }
}
