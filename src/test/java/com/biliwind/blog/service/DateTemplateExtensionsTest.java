package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTemplateExtensionsTest {

    @Test
    void formatsOffsetDateTimeWithoutRawIsoArtifacts() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 11, 20, 5, 26, 36, 0, ZoneOffset.UTC);
        String zh = DateTemplateExtensions.formatDateTime(utc, "zh-cn");
        String en = DateTemplateExtensions.formatDateTime(utc, "en-us");

        assertFalse(zh.contains("T"));
        assertFalse(zh.endsWith("Z"));
        assertTrue(zh.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));

        assertFalse(en.contains("T"));
        assertTrue(en.contains("2026"));
        assertTrue(en.contains("Nov"));
    }

    @Test
    void formatsCalendarDatesForZhAndEn() {
        LocalDate date = LocalDate.of(2026, 11, 20);
        assertEquals("2026-11-20", DateTemplateExtensions.formatDate(date, "zh-cn"));
        assertEquals("2026-11-20", DateTemplateExtensions.formatDate("2026-11-20", "zh-cn"));
        assertTrue(DateTemplateExtensions.formatDate(date, "en-us").contains("2026"));
        assertTrue(DateTemplateExtensions.formatDate("2026-11-20", "en-us").contains("Nov"));
    }

    @Test
    void handlesNullAndBlankSafely() {
        assertEquals("", DateTemplateExtensions.formatDateTime(null, "zh-cn"));
        assertEquals("", DateTemplateExtensions.formatDate(null, "en-us"));
        assertEquals("", DateTemplateExtensions.formatDateTime("   ", "zh-cn"));
        assertEquals("not-a-date", DateTemplateExtensions.formatDateTime("not-a-date", "zh-cn"));
    }

    @Test
    void convertsIsoStringToLocalZone() {
        OffsetDateTime instant = OffsetDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZoneOffset.UTC);
        String expectedDate = instant.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString();
        assertEquals(expectedDate, DateTemplateExtensions.formatDate("2026-11-20T00:00:00Z", "zh-cn"));
    }
}
