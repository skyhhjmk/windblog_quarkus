package com.biliwind.blog.service;

import com.biliwind.blog.model.OutboxEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class EmailDeliveryOutboxTest {

    @Inject
    EmailDeliveryService emailDeliveryService;

    @Test
    @Transactional
    void shouldPersistEmailAsOutboxEventBeforeDeliveryWorkerRuns() {
        String recipient = "outbox-" + UUID.randomUUID() + "@example.com";

        emailDeliveryService.queue("OUTBOX_TEST", recipient, "测试邮件", "<p>test</p>");

        OutboxEvent event = OutboxEvent.find(
                "eventType = ?1 and aggregateId = ?2 order by id desc", "EMAIL_DELIVERY", recipient)
                .firstResult();
        assertNotNull(event);
        assertEquals("EMAIL", event.aggregateType);
        assertEquals("<p>test</p>", event.payload.get("htmlContent"));
    }
}
