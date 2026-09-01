package com.hmdp.agent.config;

import cn.hutool.core.util.StrUtil;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 鉴权透传：将当前用户 token 传给下游业务服务
 * 下游（order-service 等）通过 Sa-Token 解析该头完成登录态识别与归属校验
 */
@Configuration
public class FeignAuthConfig {

    @Bean
    public RequestInterceptor agentTokenRelayInterceptor() {
        return template -> {
            String token = AgentTokenHolder.get();
            if (StrUtil.isNotBlank(token)) {
                template.header("Authorization", token);
            }
        };
    }
}
