package com.biliwind.blog.common.error;

import java.util.Locale;
import java.util.regex.Pattern;

/** Stable error codes shared by API responses, browser notifications, and docs/error-codes.md. */
public final class ErrorCodeCatalog {
    public static final String CSRF_VALIDATION_FAILED = "WB-SEC-CSRF-001";

    private static final Pattern CODE_IN_MESSAGE = Pattern.compile(
            "(?:错误码|error\\s*code)\\s*[：:]\\s*[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);

    private ErrorCodeCatalog() {
    }

    public static String resolve(String path, int status, String message) {
        String normalizedPath = normalizePath(path);
        String normalizedMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);

        if (normalizedMessage.contains("csrf") || normalizedMessage.contains("xsrf")
                || normalizedMessage.contains("csrf 验证")) {
            return CSRF_VALIDATION_FAILED;
        }

        if (normalizedPath.startsWith("/api/comments")) {
            if (status == 429 || containsAny(normalizedMessage, "过于频繁", "一小时后重试")) {
                return "WB-COMMENT-RATE-001";
            }
            if (containsAny(normalizedMessage, "验证码暂时不可用")) {
                return "WB-COMMENT-CAPTCHA-003";
            }
            if (containsAny(normalizedMessage, "验证码错误", "验证码已过期")) {
                return "WB-COMMENT-CAPTCHA-002";
            }
            if (containsAny(normalizedMessage, "请先获取验证码")) {
                return "WB-COMMENT-CAPTCHA-001";
            }
            if (containsAny(normalizedMessage, "评论功能已关闭")) {
                return "WB-COMMENT-DISABLED-001";
            }
            if (containsAny(normalizedMessage, "post not found", "文章不存在")) {
                return "WB-COMMENT-POST-001";
            }
            if (containsAny(normalizedMessage, "parent comment")) {
                return "WB-COMMENT-PARENT-001";
            }
            if (containsAny(normalizedMessage, "昵称", "邮箱地址", "站点地址", "comment content")) {
                return "WB-COMMENT-INPUT-001";
            }
            if (status >= 500) {
                return "WB-COMMENT-SERVICE-001";
            }
            if (status == 400) {
                return "WB-COMMENT-REQUEST-001";
            }
            return "WB-COMMENT-SUBMIT-001";
        }

        if (normalizedPath.startsWith("/user/api/register")) {
            if (containsAny(normalizedMessage, "注册功能暂未开放")) {
                return "WB-AUTH-REGISTER-001";
            }
            if (containsAny(normalizedMessage, "同意用户协议", "隐私政策")) {
                return "WB-AUTH-REGISTER-002";
            }
            if (containsAny(normalizedMessage, "两次输入的密码不一致")) {
                return "WB-AUTH-REGISTER-003";
            }
            if (containsAny(normalizedMessage, "用户名已被使用")) {
                return "WB-AUTH-REGISTER-004";
            }
            if (containsAny(normalizedMessage, "邮箱已被注册")) {
                return "WB-AUTH-REGISTER-005";
            }
            if (status == 429 || containsAny(normalizedMessage, "注册尝试过于频繁")) {
                return "WB-AUTH-REGISTER-006";
            }
            if (status >= 500) {
                return "WB-AUTH-REGISTER-008";
            }
            if (status == 403) {
                return "WB-HTTP-403-001";
            }
            if (status == 409) {
                return "WB-HTTP-409-001";
            }
            return status == 400 || status < 400 ? "WB-AUTH-REGISTER-007" : httpErrorCode(status);
        }

        if (normalizedPath.startsWith("/user/api/login")
                && containsAny(normalizedMessage, "账号或密码错误")) {
            return "WB-AUTH-LOGIN-001";
        }
        if (normalizedPath.startsWith("/user/api/reset-password")
                && containsAny(normalizedMessage, "重置链接无效", "密码长度", "两次输入的密码不一致")) {
            return "WB-AUTH-RESET-001";
        }

        return httpErrorCode(status);
    }

    private static String httpErrorCode(int status) {
        return switch (status) {
            case 400 -> "WB-HTTP-400-001";
            case 401 -> "WB-HTTP-401-001";
            case 403 -> "WB-HTTP-403-001";
            case 404 -> "WB-HTTP-404-001";
            case 409 -> "WB-HTTP-409-001";
            case 422 -> "WB-HTTP-422-001";
            case 429 -> "WB-HTTP-429-001";
            default -> status >= 500 ? "WB-HTTP-5XX-001" : "WB-HTTP-OTHER-001";
        };
    }

    public static String appendToMessage(String message, String errorCode) {
        String safeMessage = message == null || message.isBlank() ? "请求失败" : message.trim();
        if (errorCode == null || errorCode.isBlank() || CODE_IN_MESSAGE.matcher(safeMessage).find()) {
            return safeMessage;
        }
        String separator = safeMessage.matches(".*[。.!?！？]$") ? " " : "。";
        return safeMessage + separator + "错误码：" + errorCode;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
