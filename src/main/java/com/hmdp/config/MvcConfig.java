package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

// NOTE 这是一个 MVC 配置类，用于注册拦截器
//  核心功能是注册和配置两个拦截器：LoginInterceptor 和 RefreshTokenInterceptor


@Configuration
public class MvcConfig implements WebMvcConfigurer {

    //NOTE spring Date Redis提供的Redis的模版类 ，用于与Redis进行交互
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);
        // NOTE token刷新的拦截器 addPathPatterns("/**") 表示拦截所有请求，无论该请求是否需要登录。
        // NOTE 这样设计的目的是确保每次请求都能触发 RefreshTokenInterceptor，从而实现对用户会话的持续刷新。
        // NOTE order(0) 确保这个拦截器在 LoginInterceptor 之前执行。
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }

    //NOTE 如果两个拦截器都放行，请求最终到达 Controller。
}

//NOTE 基于这段代码，面试官可以深入考察你对 Spring Boot、Redis、拦截器、分布式会话管理等核心技术的理解。
