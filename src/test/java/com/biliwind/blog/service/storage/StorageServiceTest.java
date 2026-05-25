package com.biliwind.blog.service.storage;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class StorageServiceTest {

    @Inject
    StorageService storageService;

    @Test
    public void testServiceInjected() {
        assertNotNull(storageService);
    }

    @Test
    public void testGetPrimaryProviderName() {
        String name = storageService.getPrimaryProviderName();
        assertNotNull(name);
    }

    @Test
    public void testGetAllProviderEntities() {
        java.util.List<com.biliwind.blog.model.StorageClassEntity> entities = storageService.getAllProviderEntities();
        assertNotNull(entities);
    }
}
