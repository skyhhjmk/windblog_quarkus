package com.biliwind.blog.common.exception;

/**
 * 请求参数错误异常（HTTP 400）
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException() {
        super("请求参数错误");
    }

    public BadRequestException(String message) {
        super(message);
    }
}
