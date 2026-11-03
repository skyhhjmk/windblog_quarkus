package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConcurrencyLimiterTest {

    @Test
    void enforcesConcurrencyLimitPerProvider() {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter();
        AiProviderConfig config = provider(1L, 2);

        assertTrue(limiter.tryAcquire(config));
        assertEquals(1, limiter.getActiveCount(1L));

        assertTrue(limiter.tryAcquire(config));
        assertEquals(2, limiter.getActiveCount(1L));

        // Third concurrent request is rejected
        assertFalse(limiter.tryAcquire(config));
        assertEquals(2, limiter.getActiveCount(1L));

        // Release one permit
        limiter.release(config);
        assertEquals(1, limiter.getActiveCount(1L));

        // Now acquire succeeds again
        assertTrue(limiter.tryAcquire(config));
        assertEquals(2, limiter.getActiveCount(1L));
    }

    @Test
    void allowsUnlimitedWhenNullOrZero() {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter();
        AiProviderConfig unlimited1 = provider(1L, null);
        AiProviderConfig unlimited2 = provider(2L, 0);

        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.tryAcquire(unlimited1));
            assertTrue(limiter.tryAcquire(unlimited2));
        }
        assertEquals(0, limiter.getActiveCount(1L));
        assertEquals(0, limiter.getActiveCount(2L));
    }

    @Test
    void maintainsProvidersIndependently() {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter();
        AiProviderConfig p1 = provider(10L, 1);
        AiProviderConfig p2 = provider(20L, 1);

        assertTrue(limiter.tryAcquire(p1));
        assertFalse(limiter.tryAcquire(p1));

        // p2 is unaffected by p1
        assertTrue(limiter.tryAcquire(p2));
        assertFalse(limiter.tryAcquire(p2));

        limiter.release(p1);
        assertTrue(limiter.tryAcquire(p1));
    }

    @Test
    void handlesConcurrentThreadsCorrectly() throws Exception {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter();
        int limit = 5;
        AiProviderConfig config = provider(100L, limit);

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger acquiredCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (limiter.tryAcquire(config)) {
                        acquiredCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(limit, acquiredCount.get());
        assertEquals(limit, limiter.getActiveCount(100L));
    }

    private AiProviderConfig provider(Long id, Integer concurrencyLimit) {
        AiProviderConfig config = new AiProviderConfig();
        config.id = id;
        config.name = "provider-" + id;
        config.concurrencyLimit = concurrencyLimit;
        return config;
    }
}
