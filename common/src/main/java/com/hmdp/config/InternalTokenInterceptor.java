package com.hmdp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 内部端点鉴权（SPEC-06 §5.2 方案 A）
 *
 * <p>{@code /internal/**} 不依赖登录态——它服务于 MQ 消费线程等无请求上下文的调用方。
 * 改由共享密钥 {@code X-Internal-Token} 校验，挡住跨网段随手调用。
 *
 * <p>安全默认：密钥未配置时不放行（宁可内部调用失败，也不静默裸露）。
 */
public class InternalTokenInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Internal-Token";
    private static final String BODY = "{\"success\":false,\"errorMsg\":\"内部接口拒绝访问\"}";

    private final String expected;

    public InternalTokenInterceptor(String expected) {
        this.expected = expected;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String provided = request.getHeader(HEADER);
        if (expected != null && !expected.isBlank() && expected.equals(provided)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(BODY);
        return false;
    }
}
