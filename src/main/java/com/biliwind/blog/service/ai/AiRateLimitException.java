package com.biliwind.blog.service.ai;

/** Thrown when a provider's configured per-minute request budget is exhausted. */
public class AiRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public AiRateLimitException(String providerName, long retryAfterSeconds) {
        super("AI 提供商「" + providerName + "」已达到每分钟请求上限，请在 "
                + Math.max(1, retryAfterSeconds) + " 秒后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
