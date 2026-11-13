package com.biliwind.blog.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheServiceJitterTest {

    @Test
    void jitterStaysInsideTenPercentOfTheBaseTtl() {
        Duration base = Duration.ofMinutes(30);
        long baseSeconds = base.toSeconds();
        long min = Math.max(1L, baseSeconds - baseSeconds / 10L);
        long max = baseSeconds + baseSeconds / 10L;
        Random random = new Random(42L);

        boolean sawDifferentValue = false;
        for (int i = 0; i < 500; i++) {
            long seconds = CacheService.withJitter(base, random).toSeconds();
            assertTrue(seconds >= min && seconds <= max,
                    "jittered TTL must stay within 10%: " + seconds);
            sawDifferentValue |= seconds != baseSeconds;
        }
        assertTrue(sawDifferentValue, "jitter should actually vary the TTL");
    }

    @Test
    void tinyTtlsAreLeftUntouched() {
        assertEquals(Duration.ofSeconds(1), CacheService.withJitter(Duration.ofSeconds(1), new Random(1L)));
        assertEquals(Duration.ZERO, CacheService.withJitter(Duration.ZERO, new Random(1L)));
    }
}
