package com.biliwind.blog.service;

import com.biliwind.blog.controller.api.admin.AdminEmailDeliveryController;
import com.biliwind.blog.model.EmailDelivery;
import com.biliwind.blog.model.OutboxEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmailDeliveryOutboxTest {

    @Inject
    EmailDeliveryService emailDeliveryService;

    @Inject
    AdminEmailDeliveryController adminEmailDeliveryController;

    @Inject
    EntityManager entityManager;

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

    @Test
    @Transactional
    void shouldFailPendingDeliveriesWithoutChangingSentDeliveries() {
        String suffix = UUID.randomUUID().toString();
        EmailDelivery pending = newDelivery("pending-" + suffix, "PENDING");
        EmailDelivery inFlight = newDelivery("in-flight-" + suffix, "IN_FLIGHT");
        EmailDelivery sent = newDelivery("sent-" + suffix, "SENT");
        sent.sentAt = OffsetDateTime.now();
        pending.persist();
        inFlight.persist();
        sent.persist();
        entityManager.flush();

        AdminEmailDeliveryController.FailPendingResult result =
                adminEmailDeliveryController.failPending();

        entityManager.clear();
        EmailDelivery failedDelivery = EmailDelivery.findById(pending.id);
        EmailDelivery failedInFlightDelivery = EmailDelivery.findById(inFlight.id);
        EmailDelivery unchangedDelivery = EmailDelivery.findById(sent.id);
        assertTrue(result.failedCount() >= 2);
        assertEquals("FAILED", failedDelivery.status);
        assertEquals("管理员手动终止未发送邮件", failedDelivery.lastError);
        assertEquals("FAILED", failedInFlightDelivery.status);
        assertEquals("SENT", unchangedDelivery.status);
    }

    private EmailDelivery newDelivery(String recipient, String status) {
        EmailDelivery delivery = new EmailDelivery();
        delivery.scenario = "ADMIN_TEST";
        delivery.recipientAddress = recipient + "@example.com";
        delivery.subject = "管理测试邮件";
        delivery.htmlContent = "<p>test</p>";
        delivery.status = status;
        delivery.createdAt = OffsetDateTime.now();
        delivery.nextAttemptAt = OffsetDateTime.now();
        return delivery;
    }
}
