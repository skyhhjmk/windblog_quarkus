package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory fixed-window request limiter keyed by AI provider config ID.
 * A limit of {@code null} or {@code <= 0} means unlimited. Translation tasks
 * run on the primary node and writing/topic calls share this limiter, so a
 * polling group and each of its provider nodes are limited independently.
 */
@ApplicationScoped
public class AiRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public AiRateLimiter() {
        this(System::currentTimeMillis);
    }

    AiRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public Result tryAcquire(AiProviderConfig config) {
        if (config == null || config.id == null
                || config.rateLimitPerMinute == null || config.rateLimitPerMinute <= 0) {
            return Result.permit();
        }
        int limit = config.rateLimitPerMinute;
        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent(config.id, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startMillis >= WINDOW_MILLIS) {
                window.startMillis = now;
                window.count = 0;
            }
            if (window.count < limit) {
                window.count++;
                return Result.permit();
            }
            long retryAfter = Math.max(1,
                    (WINDOW_MILLIS - (now - window.startMillis) + 999) / 1000);
            return new Result(false, retryAfter, limit, window.count);
        }
    }

    public void reset(Long providerId) {
        if (providerId != null) {
            windows.remove(providerId);
        }
    }

    public record Result(boolean allowed, long retryAfterSeconds, int limit, int used) {
        static Result permit() {
            return new Result(true, 0, 0, 0);
        }
    }

    private static final class Window {
        private long startMillis;
        private int count;

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
