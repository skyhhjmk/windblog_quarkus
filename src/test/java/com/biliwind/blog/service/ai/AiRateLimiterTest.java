package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRateLimiterTest {

    @Test
    void enforcesFixedWindowPerProvider() {
        AtomicLong now = new AtomicLong(1_000_000L);
        AiRateLimiter limiter = new AiRateLimiter(now::get);
        AiProviderConfig config = provider(1L, 2);

        assertTrue(limiter.tryAcquire(config).allowed());
        assertTrue(limiter.tryAcquire(config).allowed());
        AiRateLimiter.Result blocked = limiter.tryAcquire(config);
        assertFalse(blocked.allowed());
        assertEquals(2, blocked.limit());
        assertTrue(blocked.retryAfterSeconds() > 0);

        now.addAndGet(60_001L);
        assertTrue(limiter.tryAcquire(config).allowed());
    }

    @Test
    void keepsProvidersIndependentAndAllowsUnlimited() {
        AtomicLong now = new AtomicLong(1_000_000L);
        AiRateLimiter limiter = new AiRateLimiter(now::get);
        AiProviderConfig first = provider(1L, 1);
        AiProviderConfig second = provider(2L, 1);
        AiProviderConfig unlimited = provider(3L, null);

        assertTrue(limiter.tryAcquire(first).allowed());
        assertFalse(limiter.tryAcquire(first).allowed());
        assertTrue(limiter.tryAcquire(second).allowed());
        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.tryAcquire(unlimited).allowed());
        }
    }

    private AiProviderConfig provider(Long id, Integer limit) {
        AiProviderConfig config = new AiProviderConfig();
        config.id = id;
        config.name = "provider-" + id;
        config.rateLimitPerMinute = limit;
        return config;
    }
}
