package com.biliwind.blog.controller.api.admin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminStorageControllerTest {

    @Test
    void shouldRemoveStoragePathsFromSyncResponse() {
        Map<String, Object> storageClasses = Map.of(
                "local", Map.of(
                        "original", Map.of(
                                "status", "synced",
                                "size", 1234,
                                "path", "/srv/windblog/uploads/private.jpg",
                                "url", "https://storage.example/private.jpg")));

        Map<String, Object> safe = AdminStorageController.safeStorageClasses(storageClasses);

        Map<?, ?> provider = (Map<?, ?>) safe.get("local");
        Map<?, ?> original = (Map<?, ?>) provider.get("original");
        assertEquals("synced", original.get("status"));
        assertEquals(1234, original.get("size"));
        assertFalse(original.containsKey("path"));
        assertFalse(original.containsKey("url"));
        assertFalse(safe.toString().contains("/srv/windblog"));
        assertFalse(safe.toString().contains("storage.example"));
    }
}
