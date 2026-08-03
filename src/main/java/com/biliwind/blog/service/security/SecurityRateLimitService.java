package com.biliwind.blog.service.security;

import com.biliwind.blog.common.CacheService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SecurityRateLimitService {

    private static final int MAX_LOCAL_WINDOWS = 10000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Inject
    CacheService cacheService;

    public boolean isAllowed(String key, int maxAttempts, Duration windowDuration) {
        return tryAcquire(key, maxAttempts, windowDuration);
    }

    public boolean tryAcquire(String key, int limit, Duration windowDuration) {
        if (limit < 1 || windowDuration == null || windowDuration.isNegative() || windowDuration.isZero()) {
            return false;
        }
        if (cacheService != null && cacheService.isAvailable()) {
            long distributedCount = cacheService.increment("security:rate:" + key, windowDuration);
            if (distributedCount >= 0) {
                return distributedCount <= limit;
            }
        }
        return tryAcquireLocally(key, limit, windowDuration);
    }

    private boolean tryAcquireLocally(String key, int limit, Duration windowDuration) {
        cleanupLocalWindows();
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            window.lastAccessAt = now;
            if (now - window.startedAt >= windowDuration.toMillis()) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count >= limit) {
                return false;
            }
            window.count = window.count + 1;
            return true;
        }
    }

    public boolean tryAcquireWeighted(String key, long amount, long limit, Duration windowDuration) {
        if (amount < 1) {
            return true;
        }
        if (limit < 1 || windowDuration == null || windowDuration.isNegative() || windowDuration.isZero()) {
            return false;
        }
        cleanupLocalWindows();
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            window.lastAccessAt = now;
            if (now - window.startedAt >= windowDuration.toMillis()) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count > limit - amount) {
                return false;
            }
            window.count = window.count + amount;
            return true;
        }
    }

    public void recordFailure(String key, Duration windowDuration) {
        if (windowDuration == null || windowDuration.isNegative() || windowDuration.isZero()) {
            return;
        }
        if (cacheService != null && cacheService.isAvailable()) {
            long distributedCount = cacheService.increment("security:rate:" + key, windowDuration);
            if (distributedCount >= 0) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        cleanupLocalWindows();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            window.lastAccessAt = now;
            if (now - window.startedAt >= windowDuration.toMillis()) {
                window.startedAt = now;
                window.count = 0;
            }
            window.count = window.count + 1;
        }
    }

    public void clear(String key) {
        windows.remove(key);
        if (cacheService != null) {
            cacheService.delete("security:rate:" + key);
        }
    }

    @Scheduled(every = "5m", identity = "security-rate-limit-local-cleanup")
    void cleanupLocalWindows() {
        long now = System.currentTimeMillis();
        long staleBefore = now - Duration.ofHours(2).toMillis();
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            if (window.lastAccessAt < staleBefore) {
                windows.remove(entry.getKey(), window);
            }
        }
        if (windows.size() <= MAX_LOCAL_WINDOWS) {
            return;
        }
        for (String key : windows.keySet()) {
            if (windows.size() <= MAX_LOCAL_WINDOWS) {
                break;
            }
            windows.remove(key);
        }
    }

    private static class Window {
        long startedAt;
        long count;
        long lastAccessAt;

        Window(long startedAt) {
            this.startedAt = startedAt;
            this.count = 0;
            this.lastAccessAt = startedAt;
        }
    }
}
