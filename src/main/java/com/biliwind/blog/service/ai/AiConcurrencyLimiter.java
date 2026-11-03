package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory concurrency limiter keyed by AI provider config ID.
 * Controls how many requests can be simultaneously in-flight to a provider.
 * A limit of {@code null} or {@code <= 0} means unlimited.
 */
@ApplicationScoped
public class AiConcurrencyLimiter {

    private final Map<Long, AtomicInteger> activeCounts = new ConcurrentHashMap<>();

    public boolean tryAcquire(AiProviderConfig config) {
        if (config == null || config.id == null
                || config.concurrencyLimit == null || config.concurrencyLimit <= 0) {
            return true;
        }
        int limit = config.concurrencyLimit;
        AtomicInteger counter = activeCounts.computeIfAbsent(config.id, ignored -> new AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release(AiProviderConfig config) {
        if (config == null || config.id == null
                || config.concurrencyLimit == null || config.concurrencyLimit <= 0) {
            return;
        }
        AtomicInteger counter = activeCounts.get(config.id);
        if (counter != null) {
            counter.updateAndGet(c -> Math.max(0, c - 1));
        }
    }

    public int getActiveCount(Long configId) {
        if (configId == null) {
            return 0;
        }
        AtomicInteger counter = activeCounts.get(configId);
        return counter == null ? 0 : counter.get();
    }

    public void reset(Long configId) {
        if (configId != null) {
            activeCounts.remove(configId);
        }
    }
}
