package com.biliwind.blog.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CacheServiceRedisTest {

    @Inject
    CacheService cacheService;

    @Test
    void hitAndMissGoThroughTheSameSeam() {
        String key = "test:getOrLoad:" + UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        Optional<String> first = cacheService.getOrLoad(key, String.class, Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return "loaded-value";
        });
        assertEquals(Optional.of("loaded-value"), first);
        assertEquals(1, loads.get());

        Optional<String> second = cacheService.getOrLoad(key, String.class, Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return "reloaded-value";
        });
        assertEquals(Optional.of("loaded-value"), second);
        assertEquals(1, loads.get(), "a cache hit must not invoke the loader");
        cacheService.delete(key);
    }

    @Test
    void unknownValueIsNegativelyCachedForRepeatedReads() {
        String key = "test:negative:" + UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        assertTrue(cacheService.getOrLoad(key, String.class, Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return null;
        }).isEmpty());
        assertEquals(1, loads.get());

        assertTrue(cacheService.getOrLoad(key, String.class, Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return "should-not-load";
        }).isEmpty());
        assertEquals(1, loads.get(), "the negative marker must short-circuit repeated lookups");

        cacheService.deletePattern(key + "*");
    }

    @Test
    void concurrentMissesInvokeTheLoaderOnce() throws Exception {
        String key = "test:singleFlight:" + UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Optional<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return cacheService.getOrLoad(key, String.class, Duration.ofMinutes(5), () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(150L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "once";
                    });
                }));
            }
            start.countDown();
            for (Future<Optional<String>> future : futures) {
                assertEquals(Optional.of("once"), future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get(), "single-flight must collapse concurrent misses into one load");
        } finally {
            pool.shutdownNow();
            cacheService.delete(key);
        }
    }
}
