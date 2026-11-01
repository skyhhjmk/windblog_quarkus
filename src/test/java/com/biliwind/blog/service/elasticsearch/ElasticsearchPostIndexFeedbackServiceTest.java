package com.biliwind.blog.service.elasticsearch;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchPostIndexFeedbackServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T10:00:00Z");

    @Test
    void contentFreshnessIgnoresMissingDatabaseTimestamps() {
        assertTrue(ElasticsearchPostIndexFeedbackService.isContentFresh(null, NOW, 10, 60));
        assertTrue(ElasticsearchPostIndexFeedbackService.isContentFresh(NOW, null, 0, 60));
    }

    @Test
    void contentFreshnessDetectsOlderIndexOrEmptyIndex() {
        assertFalse(ElasticsearchPostIndexFeedbackService.isContentFresh(NOW, null, 10, 60));
        assertFalse(ElasticsearchPostIndexFeedbackService.isContentFresh(
                NOW, NOW.minusSeconds(120), 10, 60));
        assertTrue(ElasticsearchPostIndexFeedbackService.isContentFresh(
                NOW, NOW.minusSeconds(30), 10, 60));
        assertTrue(ElasticsearchPostIndexFeedbackService.isContentFresh(NOW, NOW, 10, 60));
    }

    @Test
    void rebuildThresholdUsesRatioAndMinimumDrift() {
        assertEquals(20, ElasticsearchPostIndexFeedbackService.calculateRebuildThreshold(10, 20, 0.2));
        assertEquals(200, ElasticsearchPostIndexFeedbackService.calculateRebuildThreshold(1000, 20, 0.2));
        assertEquals(1, ElasticsearchPostIndexFeedbackService.calculateRebuildThreshold(0, 0, 0.2));
        assertEquals(1, ElasticsearchPostIndexFeedbackService.calculateRebuildThreshold(5, 0, -1));
    }
}
