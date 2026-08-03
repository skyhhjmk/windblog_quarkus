package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityRateLimitServiceTest {

    @Test
    void shouldEnforceWeightedFallbackBudget() {
        SecurityRateLimitService service = new SecurityRateLimitService();

        assertTrue(service.tryAcquireWeighted("bytes", 7, 10, Duration.ofMinutes(1)));
        assertFalse(service.tryAcquireWeighted("bytes", 4, 10, Duration.ofMinutes(1)));
    }

    @Test
    void shouldEnforceSharedFallbackBudgetAndClearIt() {
        SecurityRateLimitService service = new SecurityRateLimitService();

        assertTrue(service.tryAcquire("login", 2, Duration.ofMinutes(1)));
        assertTrue(service.tryAcquire("login", 2, Duration.ofMinutes(1)));
        assertFalse(service.tryAcquire("login", 2, Duration.ofMinutes(1)));

        service.clear("login");

        assertTrue(service.tryAcquire("login", 2, Duration.ofMinutes(1)));
    }

    @Test
    void shouldConsumeBudgetThroughIsAllowedEntryPoint() {
        SecurityRateLimitService service = new SecurityRateLimitService();

        assertTrue(service.isAllowed("legacy-entry-point", 1, Duration.ofMinutes(1)));
        assertFalse(service.isAllowed("legacy-entry-point", 1, Duration.ofMinutes(1)));
    }
}
