package com.biliwind.blog.common.exception;

/**
 * 冲突异常（HTTP 409）
 */
public class ConflictException extends RuntimeException {

    public ConflictException() {
        super("请求冲突");
    }

    public ConflictException(String message) {
        super(message);
    }
}
