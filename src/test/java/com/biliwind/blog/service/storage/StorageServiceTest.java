package com.biliwind.blog.service.storage;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class StorageServiceTest {

    @Inject
    StorageService storageService;

    @Test
    public void testGetPrimaryProviderName() {
        String name = storageService.getPrimaryProviderName();
        assertNotNull(name);
        assertFalse(name.isBlank(), "Primary provider name should not be blank");
        assertEquals("local", name);
    }

    @Test
    public void testGetAllProviderEntities() {
        java.util.List<com.biliwind.blog.model.StorageClassEntity> entities = storageService.getAllProviderEntities();
        assertNotNull(entities);
        assertFalse(entities.isEmpty(), "Should contain at least the default local_fs provider");
        assertTrue(entities.stream().anyMatch(e -> "local".equals(e.name)
                && "local_fs".equals(e.providerType)), "Should include the local local_fs entity");
    }
}
