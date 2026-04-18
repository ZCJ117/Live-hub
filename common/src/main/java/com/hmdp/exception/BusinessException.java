package com.hmdp.exception;

import lombok.Getter;

/**
 * 业务异常类，不触发事务回滚
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final String message;
    
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }
    
    public BusinessException(String message) {
        this(400, message);
    }
}