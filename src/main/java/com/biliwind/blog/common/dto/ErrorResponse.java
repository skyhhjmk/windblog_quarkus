package com.biliwind.blog.common.dto;

import java.time.OffsetDateTime;

/**
 * 统一的 API 错误响应格式。
 */
public record ErrorResponse(
        boolean success,
        String message,
        String errorType,
        OffsetDateTime timestamp,
        String trackingId,
        String trackingText
) {
    public ErrorResponse(String message, String errorType) {
        this(false, message, errorType, OffsetDateTime.now(), null, null);
    }

    public ErrorResponse(String message, String errorType, String trackingId, String trackingText) {
        this(false, message, errorType, OffsetDateTime.now(), trackingId, trackingText);
    }
}
