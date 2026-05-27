package com.biliwind.blog.service.elasticsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchConnectionManagerTest {

    @Test
    void transientHealthCheckFailureKeepsServiceAvailable() {
        ElasticsearchConnectionManager manager = new ElasticsearchConnectionManager();
        manager.healthCheckFailureThreshold = 3;
        manager.healthCheckRecoveryThreshold = 1;

        manager.recordHealthCheckResult(true, null);
        assertTrue(manager.isAvailable());

        manager.recordHealthCheckResult(false, "timeout");
        assertTrue(manager.isAvailable());

        manager.recordHealthCheckResult(false, "timeout");
        assertTrue(manager.isAvailable());

        manager.recordHealthCheckResult(false, "timeout");
        assertFalse(manager.isAvailable());
    }

    @Test
    void unavailableServiceRequiresConsecutiveSuccessesToRecover() {
        ElasticsearchConnectionManager manager = new ElasticsearchConnectionManager();
        manager.healthCheckFailureThreshold = 1;
        manager.healthCheckRecoveryThreshold = 2;

        manager.recordHealthCheckResult(true, null);
        manager.recordHealthCheckResult(false, "timeout");
        assertFalse(manager.isAvailable());

        manager.recordHealthCheckResult(true, null);
        assertFalse(manager.isAvailable());

        manager.recordHealthCheckResult(true, null);
        assertTrue(manager.isAvailable());
    }
}
