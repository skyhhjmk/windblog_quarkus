package com.biliwind.blog.common.dto;

import java.time.OffsetDateTime;

/**
 * 统一的 API 错误响应格式。
 */
public record ErrorResponse(
        boolean success,
        String message,
        String errorType,
        OffsetDateTime timestamp
) {
    public ErrorResponse(String message, String errorType) {
        this(false, message, errorType, OffsetDateTime.now());
    }
}
