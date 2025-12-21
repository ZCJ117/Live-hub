package com.hmdp.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(LoginInterceptor.class);

    // Token在请求头中的字段名（根据实际情况调整）
    private static final String TOKEN_HEADER = "Authorization";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取Token
        String token = extractToken(request);

        // 安全地记录Token（只记录部分信息）
        if (token != null) {
            logger.debug("Token intercepted - Type: {}, Prefix: {}...",
                    getTokenType(token),
                    getTokenPrefix(token));
        } else {
            logger.warn("No token found in request from IP: {}", getClientIp(request));
        }

        // 原有的用户验证逻辑
        if (UserHolder.getUser() == null) {
            logger.warn("Unauthorized access attempt with token: {} from IP: {}",
                    token != null ? "Present" : "Null",
                    getClientIp(request));
            response.setStatus(401);
            return false;
        }

        logger.debug("Token validation successful for user: {}", UserHolder.getUser());
        return true;
    }

    /**
     * 从请求中提取Token
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(TOKEN_HEADER);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return request.getHeader(TOKEN_HEADER); // 直接返回其他类型的Token
    }

    /**
     * 安全地获取Token前缀（用于日志记录）
     */
    private String getTokenPrefix(String token) {
        if (token == null || token.length() <= 8) {
            return "Invalid";
        }
        return token.substring(0, 8) + "...";
    }

    /**
     * 获取Token类型
     */
    private String getTokenType(String token) {
        if (token == null) return "Null";
        if (token.startsWith("ey")) return "JWT";
        if (token.length() == 32) return "UUID";
        if (token.length() > 100) return "Long-Token";
        return "Unknown";
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}