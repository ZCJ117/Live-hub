package com.hmdp.shop.config;

import com.hmdp.dto.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 参数校验异常处理器（SPEC-05 §5.5 / 验收 A7）。
 *
 * <p>Bean Validation 在**方法参数**上抛出的是 {@code ConstraintViolationException}，
 * Spring Boot 默认把它映射为 500。A7 要求分页参数越界返回 400，故在此显式映射。
 *
 * <p>只处理约束违例，不做 catch-all——避免把真实缺陷吞成 400。
 * 本类必须留在 {@code com.hmdp.shop.config} 包内才会被组件扫描拾取。
 */
@RestControllerAdvice
@Slf4j
public class ShopValidationExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .findFirst()
                .orElse("请求参数不合法");
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.badRequest().body(Result.fail(message));
    }
}
