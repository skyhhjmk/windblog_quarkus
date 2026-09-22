package com.biliwind.blog.service.inventory;

import java.util.Map;
import java.util.UUID;

/** Public, sanitized representation of one inventory instance. */
public record InventoryItemSnapshot(
        UUID instanceUuid,
        String itemCode,
        String definitionVersion,
        int quantity,
        int maxStackSize,
        int width,
        int height,
        int x,
        int y,
        boolean rotation,
        Long containerId,
        UUID parentInstanceUuid,
        boolean deprecated,
        Map<String, Object> metadata) {
}
