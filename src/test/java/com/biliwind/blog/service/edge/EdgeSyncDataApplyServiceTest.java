package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EdgeSyncDataApplyServiceTest {

    @Inject
    EdgeSyncDataApplyService syncDataApplyService;

    @Inject
    EdgeDataSyncService edgeDataSyncService;

    @Inject
    ObjectMapper objectMapper;

    @Test
    @Transactional
    void shouldRejectPrivateEntitiesBeforeDeserializingPayload() {
        String settingKey = "edge-sync-private-test-" + UUID.randomUUID();
        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("SYSTEM_SETTING")
                .setEntityId(1L)
                .setPayload("{\"configKey\":\"" + settingKey
                        + "\",\"configValue\":{\"apiSecret\":\"must-not-sync\"}}")
                .build();

        syncDataApplyService.apply(request);

        assertNull(SystemSetting.findByKey(settingKey));
    }

    @Test
    @Transactional
    void shouldUsePublicWhitelistForEveryFullSyncPayload() throws Exception {
        for (EdgeDataSyncService.FullSyncItem item : edgeDataSyncService.buildPublicFullSyncSnapshot(false)) {
            JsonNode payload = objectMapper.readTree(item.payload());
            assertNoSensitiveField(payload, "password");
            assertNoSensitiveField(payload, "configValue");
            assertNoSensitiveField(payload, "deletedAt");
            assertNoSensitiveField(payload, "processingError");
        }
    }

    @Test
    @Transactional
    void shouldReadFullSyncThroughBoundedKeysetBatch() {
        EdgeDataSyncService.PublicFullSyncCounts counts = edgeDataSyncService.loadPublicFullSyncCounts();
        assertTrue(counts.total() >= 0);
        assertTrue(edgeDataSyncService.loadPublicFullSyncBatch(false, "TAG", 0L, 200).size() <= 200);
        assertTrue(edgeDataSyncService.loadPublicFullSyncBatch(false, "POST", 0L, 200).size() <= 200);
    }

    private void assertNoSensitiveField(JsonNode node, String fieldName) {
        if (node.isObject()) {
            org.junit.jupiter.api.Assertions.assertFalse(node.has(fieldName),
                    "edge snapshot contains forbidden field: " + fieldName);
            node.fields().forEachRemaining(entry -> assertNoSensitiveField(entry.getValue(), fieldName));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoSensitiveField(child, fieldName);
            }
        }
    }
}
