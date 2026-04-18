package com.hmdp.order.handler;

import com.hmdp.dto.Result;
import com.hmdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理类
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    /**
     * 处理业务异常，不触发事务回滚
     */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }
    
    /**
     * 处理其他异常，触发事务回滚
     */
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("Exception: {}", e.getMessage(), e);
        return Result.fail("系统繁忙，请稍后重试");
    }
}