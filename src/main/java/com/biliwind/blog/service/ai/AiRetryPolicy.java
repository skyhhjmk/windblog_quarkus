package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * The single durable-retry budget for AI work. Transports (RabbitMQ
 * redelivery, the translation database queue, provider fallback) still deliver
 * retries differently, but they all read the same attempt cap and backoff.
 */
@ApplicationScoped
public class AiRetryPolicy {

    @ConfigProperty(name = "windblog.ai.retry.max-attempts", defaultValue = "3")
    int maxAttempts;

    public int maxAttempts() {
        return Math.max(1, maxAttempts);
    }

    /** Capped linear backoff; attempt starts at 1. */
    public Duration retryDelay(int attempt) {
        long seconds = Math.max(5L, Math.min(900L, 15L * Math.max(1, attempt)));
        return Duration.ofSeconds(seconds);
    }
}
