package com.biliwind.blog.service;

import com.biliwind.blog.model.ContentAccessTicket;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MediaDownloadAuditServiceTest {

    @Test
    void shouldCalculateNonNegativeTicketAge() {
        ContentAccessTicket ticket = new ContentAccessTicket();
        OffsetDateTime issuedAt = OffsetDateTime.parse("2026-08-03T10:00:00Z");
        ticket.createdAt = issuedAt;

        assertEquals(2500L, MediaDownloadAuditService.calculateTicketAgeMillis(
                ticket, issuedAt.plusNanos(2_500_000_000L)));
        assertEquals(0L, MediaDownloadAuditService.calculateTicketAgeMillis(
                ticket, issuedAt.minusSeconds(1)));
    }

    @Test
    void shouldReturnNullWhenTicketTimestampIsUnavailable() {
        ContentAccessTicket ticket = new ContentAccessTicket();

        assertNull(MediaDownloadAuditService.calculateTicketAgeMillis(ticket, OffsetDateTime.now()));
        assertNull(MediaDownloadAuditService.calculateTicketAgeMillis(null, OffsetDateTime.now()));
    }
}
