package com.hmdp.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;
import com.hmdp.dto.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 异常处理器，将 Sa-Token 异常适配为项目统一的 Result 格式
 * 同时设置真实 HTTP 状态码（SPEC-06 §1.7）：未登录 401、无权限 403，
 * 否则前端与监控无法按状态码识别鉴权失败。
 */
@RestControllerAdvice
@Slf4j
public class SaTokenExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public Result handleNotLoginException(NotLoginException e, HttpServletResponse response) {
        log.warn("Sa-Token 未登录异常: type={}, message={}", e.getType(), e.getMessage());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return Result.fail("未登录，请先登录");
    }

    @ExceptionHandler(NotRoleException.class)
    public Result handleNotRoleException(NotRoleException e, HttpServletResponse response) {
        log.warn("Sa-Token 无角色权限: role={}, message={}", e.getRole(), e.getMessage());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return Result.fail("无角色权限");
    }

    @ExceptionHandler(NotPermissionException.class)
    public Result handleNotPermissionException(NotPermissionException e, HttpServletResponse response) {
        log.warn("Sa-Token 无此权限: permission={}, message={}", e.getPermission(), e.getMessage());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return Result.fail("无此权限");
    }

    @ExceptionHandler(SaTokenException.class)
    public Result handleSaTokenException(SaTokenException e, HttpServletResponse response) {
        log.error("Sa-Token 异常: code={}, message={}", e.getCode(), e.getMessage());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return Result.fail("认证服务异常");
    }
}
