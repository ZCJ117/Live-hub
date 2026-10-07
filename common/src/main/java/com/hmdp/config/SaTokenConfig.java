package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 与内部端点自动配置，仅在 Servlet 容器中生效（排除 WebFlux 网关）
 * 通过 AutoConfiguration.imports 注册，确保引用 common 的所有业务服务自动加载
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SaTokenConfig implements WebMvcConfigurer {

    private final String internalToken;

    public SaTokenConfig(@Value("${hmdp.internal-token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 内部端点：不走登录态，走共享密钥
        registry.addInterceptor(new InternalTokenInterceptor(internalToken))
                .addPathPatterns("/internal/**");
    }
}
