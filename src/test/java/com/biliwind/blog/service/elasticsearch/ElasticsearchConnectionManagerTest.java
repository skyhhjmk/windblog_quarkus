package com.biliwind.blog.service.elasticsearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchConnectionManagerTest {

    @Test
    void transientHealthCheckFailureKeepsServiceAvailable() {
        ElasticsearchConnectionManager manager = createEnabledManager();
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
        ElasticsearchConnectionManager manager = createEnabledManager();
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

    private ElasticsearchConnectionManager createEnabledManager() {
        ElasticsearchSettingsService.ElasticsearchSettings enabledSettings =
                new ElasticsearchSettingsService.ElasticsearchSettings(
                        true,
                        "http://127.0.0.1:9200",
                        "",
                        "",
                        5,
                        "ik_max_word",
                        List.of()
                );
        ElasticsearchConnectionManager manager = new ElasticsearchConnectionManager();
        manager.settingsService = new ElasticsearchSettingsService() {
            @Override
            public ElasticsearchSettings getSettings() {
                return enabledSettings;
            }
        };
        return manager;
    }
}
