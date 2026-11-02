package com.biliwind.blog.common.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;

/**
 * 统一的 API 错误响应格式。
 * <p>
 * The record is only produced by {@code GlobalExceptionMapper}, so Quarkus
 * cannot infer it from a REST endpoint signature; native image needs the
 * explicit reflection registration to serialize 4xx/5xx JSON bodies.
 */
@RegisterForReflection
public record ErrorResponse(
        boolean success,
        String message,
        String errorType,
        OffsetDateTime timestamp,
        String trackingId,
        String trackingText,
        String errorCode
) {
    public ErrorResponse(String message, String errorType) {
        this(false, message, errorType, OffsetDateTime.now(java.time.ZoneOffset.UTC), null, null, null);
    }

    public ErrorResponse(String message, String errorType, String trackingId, String trackingText) {
        this(false, message, errorType, OffsetDateTime.now(java.time.ZoneOffset.UTC), trackingId, trackingText, null);
    }

    public ErrorResponse(String message, String errorType, String trackingId, String trackingText, String errorCode) {
        this(false, message, errorType, OffsetDateTime.now(java.time.ZoneOffset.UTC), trackingId, trackingText,
                errorCode);
    }
}
