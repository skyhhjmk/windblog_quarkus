package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MediaDownloadRiskServiceTest {

    @Inject
    MediaDownloadRiskService riskService;

    @Inject
    CacheService cacheService;

    @Test
    void shouldKeepLocalByteLimitWhenRedisIncrementFails() throws Exception {
        Field availableField = CacheService.class.getDeclaredField("redisAvailable");
        availableField.setAccessible(true);
        boolean originalAvailability = availableField.getBoolean(cacheService);
        availableField.setBoolean(cacheService, true);

        Field valueCommandsField = CacheService.class.getDeclaredField("valueCommands");
        valueCommandsField.setAccessible(true);
        Object originalValueCommands = valueCommandsField.get(cacheService);
        valueCommandsField.set(cacheService, null);

        try {
            Long subjectId = Math.abs(UUID.randomUUID().getMostSignificantBits());
            String clientIp = "redis-fallback-" + UUID.randomUUID();
            long singleDownloadBytes = 400L * 1024L * 1024L;

            for (int attempt = 0; attempt < 5; attempt++) {
                assertTrue(riskService.check(subjectId, null, clientIp, singleDownloadBytes).allowed());
            }

            MediaDownloadRiskService.Decision denied = riskService.check(
                    subjectId, null, clientIp, singleDownloadBytes);

            assertEquals("SUBJECT_BYTE_LIMIT", denied.reason());
            assertEquals(600, denied.retryAfterSeconds());
        } finally {
            valueCommandsField.set(cacheService, originalValueCommands);
            availableField.setBoolean(cacheService, originalAvailability);
        }
    }

    @Test
    void shouldLimitRapidReuseOfOneDownloadTicketWhenRedisIsUnavailable() throws Exception {
        Field availableField = CacheService.class.getDeclaredField("redisAvailable");
        availableField.setAccessible(true);
        boolean originalAvailability = availableField.getBoolean(cacheService);
        availableField.setBoolean(cacheService, true);

        Field valueCommandsField = CacheService.class.getDeclaredField("valueCommands");
        valueCommandsField.setAccessible(true);
        Object originalValueCommands = valueCommandsField.get(cacheService);
        valueCommandsField.set(cacheService, null);

        try {
            Long subjectId = Math.abs(UUID.randomUUID().getMostSignificantBits());
            Long ticketId = Math.abs(UUID.randomUUID().getMostSignificantBits());
            String clientIp = "ticket-reuse-" + UUID.randomUUID();

            for (int attempt = 0; attempt < 24; attempt++) {
                assertTrue(riskService.check(subjectId, 8801L, clientIp, null, ticketId).allowed());
            }

            MediaDownloadRiskService.Decision denied = riskService.check(
                    subjectId, 8801L, clientIp, null, ticketId);

            assertEquals("TICKET_BEHAVIOR_LIMIT", denied.reason());
            assertEquals(600, denied.retryAfterSeconds());
        } finally {
            valueCommandsField.set(cacheService, originalValueCommands);
            availableField.setBoolean(cacheService, originalAvailability);
        }
    }
}
