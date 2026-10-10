package com.hmdp.order.handler;

import com.hmdp.dto.Result;
import com.hmdp.exception.SeckillException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.ConnectException;

@RestControllerAdvice
@Slf4j
public class SeckillExceptionHandler {

    /**
     * 秒杀入参校验失败 → 400（SPEC-14 P0-4 / §7 M3）
     *
     * <p>必须显式映射：本类与 {@code GlobalExceptionHandler} 的 {@code Exception} 兜底都返回 500，
     * 若不写这个 handler，非法入参会以 500 呈现，与验收标准「非法 voucherId 返回 400」不符。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("参数不合法");
        log.warn("秒杀入参校验失败: {}", message);
        return Result.fail(message);
    }

    @ExceptionHandler(SeckillException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleSeckillException(SeckillException e) {
        log.error("秒杀业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleRedisConnectionFailure(RedisConnectionFailureException e) {
        log.error("Redis连接失败，触发降级: {}", e.getMessage());
        return Result.fail("系统繁忙，请稍后重试");
    }

    @ExceptionHandler(ConnectException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleConnectException(ConnectException e) {
        log.error("网络连接异常: {}", e.getMessage());
        return Result.fail("网络异常，请稍后重试");
    }

    @ExceptionHandler(MQClientException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleMQClientException(MQClientException e) {
        log.error("消息队列异常: {}", e.getMessage());
        return Result.fail("系统处理中，请稍后查询订单状态");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleGenericException(Exception e) {
        log.error("系统异常: ", e);
        return Result.fail("系统异常，请稍后重试");
    }
}
