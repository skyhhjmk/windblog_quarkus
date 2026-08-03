package com.biliwind.blog.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ContentAccessTicketServiceTest {

    @Inject
    ContentAccessTicketService ticketService;

    @Test
    @Transactional
    void shouldBindMediaTicketToDeviceAndAllowScopedRevocation() {
        String deviceId = "device-a-" + UUID.randomUUID();
        ContentAccessTicketService.IssuedTicket issued = ticketService.issueMediaDownloadTicket(
                1001L, 2001L, 3001L, Duration.ofMinutes(5), deviceId);

        assertNotNull(ticketService.requireMediaTicket(issued.token(), 3001L, deviceId));
        assertNull(ticketService.requireMediaTicket(issued.token(), 3001L, "device-b"));
        assertNull(ticketService.requireMediaTicket(issued.token(), 3001L, null));

        assertEquals(1L, ticketService.revokeMediaDownloadTickets(null, null, null, deviceId));
        assertNull(ticketService.requireMediaTicket(issued.token(), 3001L, deviceId));
    }

    @Test
    @Transactional
    void shouldBindPasswordTicketToDeviceWhenDeviceBindingIsProvided() {
        String deviceId = "password-device-" + UUID.randomUUID();
        ContentAccessTicketService.IssuedTicket issued = ticketService.issuePasswordTicket(
                2101L, Duration.ofMinutes(5), deviceId);

        assertTrue(ticketService.isValid(issued.token(), "POST_PASSWORD", 2101L, null, deviceId));
        assertFalse(ticketService.isValid(issued.token(), "POST_PASSWORD", 2101L, null, "other-device"));
        assertFalse(ticketService.isValid(issued.token(), "POST_PASSWORD", 2101L, null, null));
    }

    @Test
    @Transactional
    void shouldInvalidateAllTicketsWhenKeyVersionRotates() {
        ContentAccessTicketService.IssuedTicket issued = ticketService.issueMediaDownloadTicket(
                1002L, 2002L, 3002L, Duration.ofMinutes(5));

        assertNotNull(ticketService.requireMediaTicket(issued.token(), 3002L));
        int version = ticketService.rotateKeyVersion();
        assertTrue(version > 0);
        assertNull(ticketService.requireMediaTicket(issued.token(), 3002L));
    }

    @Test
    @Transactional
    void shouldConsumeAdminMediaTicketOnlyOnceForIssuingAdmin() {
        ContentAccessTicketService.IssuedTicket issued = ticketService.issueAdminMediaDownloadTicket(
                1003L, 2003L, 3003L, Duration.ofMinutes(2));

        assertNotNull(ticketService.consumeAdminMediaDownloadTicket(issued.token(), 3003L));
        assertNull(ticketService.consumeAdminMediaDownloadTicket(issued.token(), 3003L));
        assertNull(ticketService.consumeAdminMediaDownloadTicket(issued.token(), 3004L));
    }
}
