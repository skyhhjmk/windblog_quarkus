package com.biliwind.blog.common.exception;

/**
 * 余额不足异常
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException() {
        super("余额不足");
    }

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
