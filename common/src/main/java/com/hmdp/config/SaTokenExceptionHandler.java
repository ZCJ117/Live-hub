package com.hmdp.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 异常处理器，将 Sa-Token 异常适配为项目统一的 Result 格式
 */
@RestControllerAdvice
@Slf4j
public class SaTokenExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public Result handleNotLoginException(NotLoginException e) {
        log.warn("Sa-Token 未登录异常: type={}, message={}", e.getType(), e.getMessage());
        return Result.fail("未登录，请先登录");
    }

    @ExceptionHandler(NotRoleException.class)
    public Result handleNotRoleException(NotRoleException e) {
        log.warn("Sa-Token 无角色权限: role={}, message={}", e.getRole(), e.getMessage());
        return Result.fail("无角色权限");
    }

    @ExceptionHandler(NotPermissionException.class)
    public Result handleNotPermissionException(NotPermissionException e) {
        log.warn("Sa-Token 无此权限: permission={}, message={}", e.getPermission(), e.getMessage());
        return Result.fail("无此权限");
    }

    @ExceptionHandler(SaTokenException.class)
    public Result handleSaTokenException(SaTokenException e) {
        log.error("Sa-Token 异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail("认证服务异常");
    }
}
