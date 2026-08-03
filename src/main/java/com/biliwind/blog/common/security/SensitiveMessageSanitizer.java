package com.biliwind.blog.common.security;

/** Redacts credentials before an exception or external reference reaches logs or a response. */
public final class SensitiveMessageSanitizer {

    private static final int MAX_LENGTH = 500;

    private SensitiveMessageSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String sanitized = message
                .replaceAll("(?i)(password|passwd|pwd|secret|token|api[_-]?key)(\\s*[=:]\\s*)[^\\s&;,)}]+",
                        "$1$2[REDACTED]")
                .replaceAll("(?i)(//[^/\\s:@]+):([^/@\\s]+)@", "$1:[REDACTED]@");
        if (sanitized.length() > MAX_LENGTH) {
            return sanitized.substring(0, MAX_LENGTH) + "...";
        }
        return sanitized;
    }
}
