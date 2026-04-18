package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;

/**
 * Web MVC配置类
 * 配置拦截器及其执行顺序
 */
@Configuration
@EnableConfigurationProperties(InterceptorProperties.class)
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private InterceptorProperties interceptorProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 刷新Token拦截器，order=1，优先执行
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .order(1);

        // 登录验证拦截器，order=2，后执行
        registry.addInterceptor(new LoginInterceptor())
                .order(2)
                .excludePathPatterns(getExcludePaths());
    }

    /**
     * 获取排除路径列表，如果配置为空则使用默认路径
     */
    private String[] getExcludePaths() {
        List<String> excludePaths = interceptorProperties.getExcludePaths();
        if (excludePaths == null || excludePaths.isEmpty()) {
            return new String[] {
                "/user/code",
                "/user/login",
                "/actuator/**"
            };
        }
        return excludePaths.toArray(new String[0]);
    }
}