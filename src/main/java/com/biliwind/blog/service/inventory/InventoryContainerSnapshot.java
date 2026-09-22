package com.biliwind.blog.service.inventory;

import java.util.UUID;

public record InventoryContainerSnapshot(
        Long id,
        Long userId,
        UUID parentItemUuid,
        int rows,
        int columns,
        long revision) {
}
