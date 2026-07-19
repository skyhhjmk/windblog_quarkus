package com.biliwind.blog.service.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SecurityRateLimitService {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean isAllowed(String key, int maxAttempts, Duration windowDuration) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= windowDuration.toMillis()) {
                window.startedAt = now;
                window.count = 0;
            }
            return window.count < maxAttempts;
        }
    }

    public void recordFailure(String key, Duration windowDuration) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= windowDuration.toMillis()) {
                window.startedAt = now;
                window.count = 0;
            }
            window.count = window.count + 1;
        }
    }

    public void clear(String key) {
        windows.remove(key);
    }

    private static class Window {
        long startedAt;
        int count;

        Window(long startedAt) {
            this.startedAt = startedAt;
            this.count = 0;
        }
    }
}