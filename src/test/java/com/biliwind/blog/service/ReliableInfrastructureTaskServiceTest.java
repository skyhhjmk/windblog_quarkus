package com.biliwind.blog.service;

import com.biliwind.blog.model.OutboxEvent;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReliableInfrastructureTaskServiceTest {

    @Inject
    ReliableInfrastructureTaskService reliableInfrastructureTaskService;

    @Test
    @Transactional
    void shouldPersistDelayedStorageRetryInOutbox() {
        OffsetDateTime before = OffsetDateTime.now();
        StorageSyncMessage task = new StorageSyncMessage(
                880001L, "archive", "WEBP", 2);

        reliableInfrastructureTaskService.enqueueStorageSync(task, Duration.ofMinutes(2));

        OutboxEvent event = OutboxEvent.find(
                "eventKey", "STORAGE_REPLAY:880001:archive:WEBP:2").firstResult();
        assertNotNull(event);
        assertEquals("STORAGE_SYNC", event.eventType);
        assertEquals("archive", event.payload.get("storageClassName"));
        assertTrue(event.availableAt.isAfter(before.plusSeconds(60)));
    }
}
