package com.hmdp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 自动配置，仅在 Servlet 容器中生效（排除 WebFlux 网关）
 * 通过 AutoConfiguration.imports 注册，确保引用 common 的所有业务服务自动加载
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SaTokenConfig {

}
