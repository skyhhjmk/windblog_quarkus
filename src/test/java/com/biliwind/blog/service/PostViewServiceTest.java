package com.biliwind.blog.service;

import com.biliwind.blog.service.security.SecurityRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostViewServiceTest {

    private PostViewService service;
    private Map<String, Long> dbStore;
    private AtomicInteger batchWriteCalls;
    private AtomicBoolean failNextFlush;
    private Set<String> rateKeys;

    @BeforeEach
    void setup() throws Exception {
        dbStore = new HashMap<>();
        dbStore.put("post-a", 100L);
        dbStore.put("post-b", 5L);
        batchWriteCalls = new AtomicInteger(0);
        failNextFlush = new AtomicBoolean(false);
        rateKeys = new HashSet<>();

        service = new PostViewService() {
            @Override
            protected Map<String, Long> batchUpdateDatabase(Map<String, Long> deltas) {
                batchWriteCalls.incrementAndGet();
                if (failNextFlush.get()) {
                    throw new RuntimeException("simulated db outage");
                }
                Map<String, Long> updated = new HashMap<>();
                for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                    long total = dbStore.getOrDefault(entry.getKey(), 0L) + entry.getValue();
                    dbStore.put(entry.getKey(), total);
                    updated.put(entry.getKey(), total);
                }
                return updated;
            }
        };

        Field rateField = PostViewService.class.getDeclaredField("rateLimitService");
        rateField.setAccessible(true);
        rateField.set(service, new SecurityRateLimitService() {
            @Override
            public boolean tryAcquire(String key, int limit, Duration window) {
                return rateKeys.add(key);
            }
        });

        Field baseField = PostViewService.class.getDeclaredField("localBaseCounts");
        baseField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> baseMap = (Map<String, Long>) baseField.get(service);
        baseMap.putAll(dbStore);
    }

    @Test
    void shouldAggregateViewsAndFlushOnceInBatch() {
        assertEquals(Optional.of(101L), service.recordView("post-a", "1.1.1.1"));
        assertEquals(Optional.of(102L), service.recordView("post-a", "2.2.2.2"));
        // Same IP within dedup window does not increment again
        assertEquals(Optional.of(102L), service.recordView("post-a", "2.2.2.2"));
        assertEquals(Optional.of(6L), service.recordView("post-b", "1.1.1.1"));

        // No DB writes happened during individual view recordings
        assertEquals(0, batchWriteCalls.get());
        assertEquals(100L, dbStore.get("post-a"));
        assertEquals(5L, dbStore.get("post-b"));

        // Live read reflects base + aggregated pending delta
        Map<String, Long> live = service.getViewCounts(List.of("post-a", "post-b"));
        assertEquals(102L, live.get("post-a"));
        assertEquals(6L, live.get("post-b"));

        // Single batch flush writes all deltas at once
        int flushed = service.flushPendingViews();
        assertEquals(2, flushed);
        assertEquals(1, batchWriteCalls.get());
        assertEquals(102L, dbStore.get("post-a"));
        assertEquals(6L, dbStore.get("post-b"));

        // Subsequent flush with no new views does nothing
        assertEquals(0, service.flushPendingViews());
        assertEquals(1, batchWriteCalls.get());
    }

    @Test
    void shouldRestorePendingDeltasWhenBatchFlushFails() {
        assertEquals(Optional.of(101L), service.recordView("post-a", "3.3.3.3"));
        failNextFlush.set(true);

        assertEquals(0, service.flushPendingViews());
        assertEquals(100L, dbStore.get("post-a"));
        assertTrue(service.getViewCount("post-a").orElse(0L) == 101L);

        failNextFlush.set(false);
        assertEquals(1, service.flushPendingViews());
        assertEquals(101L, dbStore.get("post-a"));
    }
}
