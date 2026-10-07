package com.hmdp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Sa-Token 与内部端点自动配置，仅在 Servlet 容器中生效（排除 WebFlux 网关）
 * 通过 AutoConfiguration.imports 注册，确保引用 common 的所有业务服务自动加载
 *
 * <p>补上服务侧鉴权拦截（SPEC-06 §5.1）：此前鉴权只在网关一层，业务服务端口
 * 直接绑定 0.0.0.0，任何能访问该端口的人无需 token 即可调用全部接口。
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass(SaInterceptor.class)
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 免登录路径。其中 {@code /user/login}、{@code /user/code}、{@code /actuator/**}
     * 三项与网关 SaTokenGatewayConfig 的既有白名单一致；{@code /internal/**} 与
     * {@code /error} 是服务侧额外放开的——前者由 InternalTokenInterceptor 用共享密钥保护，
     * 不走登录态，后者承接容器转发的错误页。
     */
    static final Set<String> LOGIN_EXCLUDE_PATHS = Set.of(
            "/user/login", "/user/code", "/internal/**", "/actuator/**", "/error");

    private final String internalToken;

    public SaTokenConfig(@Value("${hmdp.internal-token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    /**
     * 注册角色来源。Sa-Token 通过 StpInterface 解析 {@code @SaCheckRole}。
     * 此处显式注册而非依赖扫描——common 的 com.hmdp.config 不在各服务的扫描范围内。
     */
    @Bean
    public StpInterface stpInterface(@Value("${hmdp.admin-user-ids:}") String adminUserIds) {
        return new AdminRoleProvider(adminUserIds);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 内部端点：不走登录态，走共享密钥
        registry.addInterceptor(new InternalTokenInterceptor(internalToken))
                .addPathPatterns("/internal/**");

        // 业务接口：校验登录态
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(LOGIN_EXCLUDE_PATHS.toArray(String[]::new));
    }
}
