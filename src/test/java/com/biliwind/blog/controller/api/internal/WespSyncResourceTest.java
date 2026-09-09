package com.biliwind.blog.controller.api.internal;

import com.biliwind.blog.service.edge.WespSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WespSyncResourceTest {
    private WespSyncResource resource;

    @BeforeEach
    void setUp() throws Exception {
        resource = new WespSyncResource();
        Field field = WespSyncResource.class.getDeclaredField("sync");
        field.setAccessible(true);
        field.set(resource, new FakeWespSyncService());
    }

    @Test
    void rejectsMissingOrInvalidAuthenticationBeforeReadingBatch() {
        ResponseAssertions.assertStatus(401, resource.batch(
                UUID.randomUUID().toString(), "{}", null, "peer", UUID.randomUUID().toString()));
    }

    @Test
    void acceptsAuthenticatedBatchAndPreservesCreatedResponse() {
        String requestId = UUID.randomUUID().toString();
        String batchId = UUID.randomUUID().toString();
        ResponseAssertions.assertStatus(201, resource.batch(batchId, "{}", "Bearer test-token", "peer", requestId));
    }

    @Test
    void rejectsNonUuidRequestId() {
        ResponseAssertions.assertStatus(400, resource.changes("0", "1", "Bearer test-token", "peer", "bad-id"));
    }

    private static final class ResponseAssertions {
        private static void assertStatus(int expected, jakarta.ws.rs.core.Response response) {
            try {
                assertEquals(expected, response.getStatus());
            } finally {
                response.close();
            }
        }
    }

    private static final class FakeWespSyncService extends WespSyncService {
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isAuthorized(String authorization, String nodeId, String body) {
            return "Bearer test-token".equals(authorization);
        }
        @Override public Map<String, Object> receiveBatch(String expectedBatchId, String body) {
            return Map.of("batch_id", expectedBatchId, "results", List.of(), "status", "RECEIVED");
        }
    }
}
