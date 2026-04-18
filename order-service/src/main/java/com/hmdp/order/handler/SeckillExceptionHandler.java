package com.hmdp.order.handler;

import com.hmdp.dto.Result;
import com.hmdp.exception.SeckillException;
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
