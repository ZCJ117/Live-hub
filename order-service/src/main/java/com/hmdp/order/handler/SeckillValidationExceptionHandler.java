package com.hmdp.order.handler;

import com.hmdp.dto.Result;
import com.hmdp.order.controller.VoucherOrderController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 秒杀入参校验异常处理器（SPEC-14 P0-4 / 验收 3）。
 *
 * <p>验收 3 要求「非法 voucherId（0 / 负数 / 超大值）返回 400」，对应两类异常：
 * <ul>
 *   <li>{@code 0} / 负数 → 方法参数上的 Bean Validation 抛 {@link ConstraintViolationException}
 *       （由 {@code @Validated} + {@code @Positive} 触发），Spring Boot 默认映射为 500；</li>
 *   <li>超大值（超出 {@code Long} 表示范围）→ 路径变量转换阶段就抛
 *       {@link MethodArgumentTypeMismatchException}，根本进不到 {@code @Positive}。</li>
 * </ul>
 * 两类都必须显式映射，否则都会落到下一段所述的兜底上。
 *
 * <p><b>为何单独成类、显式定序、且限定控制器</b>：order-service 另有一个
 * {@link GlobalExceptionHandler} 声明了 {@code @ExceptionHandler(Exception.class)} 兜底，
 * 上述两类异常它都"能处理"。Spring 的 {@code ExceptionHandlerExceptionResolver} 跨 advice
 * <b>按顺序取第一个能处理的，不比较异常类型的具体性</b>——两者都不定序时兜底会胜出，
 * 实际返回 200 +「系统繁忙，请稍后重试」，本映射形同死码（2026-10-10 端到端实测确认）。
 * 故：
 * <ol>
 *   <li>以 {@link Ordered#HIGHEST_PRECEDENCE} 定序抢先——与 {@code SaTokenExceptionHandler}
 *       对付同一陷阱的既有做法一致；</li>
 *   <li>只处理上述两类异常、不做 catch-all（同 {@code ShopValidationExceptionHandler}），
 *       使其余异常的处理路径一字不变；</li>
 *   <li>{@code assignableTypes} 限定到 {@link VoucherOrderController}，把影响面钉死在
 *       秒杀入口，不波及其他控制器。</li>
 * </ol>
 */
@RestControllerAdvice(assignableTypes = VoucherOrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class SeckillValidationExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("参数不合法");
        log.warn("秒杀入参校验失败: {}", message);
        return ResponseEntity.badRequest().body(Result.fail(message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("秒杀入参类型非法: name={}, value={}", e.getName(), e.getValue());
        return ResponseEntity.badRequest().body(Result.fail("券ID必须为正数"));
    }
}
