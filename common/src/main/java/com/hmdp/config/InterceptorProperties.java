package com.hmdp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截器配置属性
 */
@ConfigurationProperties(prefix = "hmdp.interceptor")
public class InterceptorProperties {

    /**
     * 排除路径列表，不需要登录拦截的路径
     */
    private List<String> excludePaths = new ArrayList<>();

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }
}