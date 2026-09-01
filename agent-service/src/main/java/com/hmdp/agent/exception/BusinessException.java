package com.hmdp.agent.exception;

/**
 * 业务异常（友好话术直接透出给用户）
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
