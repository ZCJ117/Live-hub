package com.hmdp.exception;

public class SeckillException extends RuntimeException {

    private Integer code;

    public SeckillException(String message) {
        super(message);
        this.code = 500;
    }

    public SeckillException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public SeckillException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }

    public Integer getCode() {
        return code;
    }
}
