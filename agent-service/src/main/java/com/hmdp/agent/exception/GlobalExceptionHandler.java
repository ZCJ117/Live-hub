package com.hmdp.agent.exception;

import com.hmdp.agent.dto.SseEvent;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理（输入校验与错误处理）
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常 → 200 + Result.fail（前端按 errorMsg 提示气泡） */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusiness(BusinessException e) {
        return Result.fail(e.getMessage());
    }

    /** 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.fail(msg);
    }

    /** SSE 业务执行异常（在异步线程内抛出，由编排层捕获转 error 事件；此处兜底） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleOther(Exception e) {
        log.error("agent-service 未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("服务器异常，请稍后再试"));
    }
}
