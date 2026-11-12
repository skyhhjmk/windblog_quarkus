package com.biliwind.blog.service.edge;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EdgeSyncRegistryTest {

    @Inject
    EdgeSyncRegistry registry;

    @Test
    void shouldDeclareOutboundAndInboundVocabularyInOnePlace() {
        for (String type : List.of("POST", "TAG", "CATEGORY", "MEDIA", "SYSTEM_SETTING", "REGION_RULE")) {
            assertTrue(registry.isPropagated(type), type);
            assertTrue(registry.isEdgeApplied(type), type);
        }
        assertFalse(registry.isPropagated("NODE_REGION"));
        assertTrue(registry.isEdgeApplied("NODE_REGION"));
        assertFalse(registry.isPropagated("CLUSTER_PUBLIC_KEY"));
        assertTrue(registry.isEdgeApplied("CLUSTER_PUBLIC_KEY"));
        assertFalse(registry.acceptsInbound("UNKNOWN"));
    }

    @Test
    void shouldAcceptTransportTypesWithoutTreatingThemAsAppliableData() {
        for (String type : List.of("MEDIA_MANIFEST", "SYNC_REQUEST")) {
            assertTrue(registry.acceptsInbound(type), type);
            assertFalse(registry.isEdgeApplied(type), type);
            assertFalse(registry.isPropagated(type), type);
        }
        assertEquals(EdgeSyncEntity.TransportKind.MEDIA_MANIFEST, registry.transportKind("MEDIA_MANIFEST"));
        assertEquals(EdgeSyncEntity.TransportKind.FULL_SYNC_REQUEST, registry.transportKind("SYNC_REQUEST"));
        assertEquals(EdgeSyncEntity.TransportKind.NONE, registry.transportKind("TAG"));
    }
}
