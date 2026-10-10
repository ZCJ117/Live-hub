package com.hmdp.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * 网关 Sa-Token 统一登录校验
 * 拦截所有请求，校验登录状态，排除登录/验证码等公开接口
 */
@Configuration
public class SaTokenGatewayConfig {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                // 拦截所有请求
                .addInclude("/**")
                // 白名单：无需登录的接口
                .addExclude("/user/login", "/user/code", "/actuator/**")
                // 认证函数：校验是否登录
                .setAuth(obj -> {
                    SaRouter.match("/**", r -> StpUtil.checkLogin());
                })
                // 异常处理：返回统一 Result 格式 + 真实状态码
                .setError(e -> {
                    // setError 的返回值只被写进响应体（SaReactorOperateUtil.writeResult 用
                    // String.valueOf 写入），**不会**动状态码——不显式设置则鉴权失败也是 HTTP 200，
                    // 前端与监控无法按状态码识别，还会掩盖路由缺失（SPEC-06 §1.7 / BUG-03）
                    SaHolder.getResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
                    // writeResult 只在不带 Content-Type 时才补 text/plain，这里先声明 JSON
                    SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=utf-8");
                    Result result = Result.fail("未登录，请先登录");
                    try {
                        return objectMapper.writeValueAsString(result);
                    } catch (JsonProcessingException ex) {
                        return "{\"success\":false,\"errorMsg\":\"未登录，请先登录\"}";
                    }
                });
    }
}
