package com.biliwind.blog.service.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventorySnapshotTest {
    @Test
    void snapshotCollectionsAreImmutableAndDefaulted() {
        InventorySnapshot snapshot = new InventorySnapshot(3, null, null, null);
        assertEquals(List.of(), snapshot.items());
        assertEquals(List.of(), snapshot.containers());
        assertEquals(Map.of(), snapshot.definitions());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.items().add(null));
    }

    @Test
    void itemSnapshotKeepsInstanceAndLayoutFields() {
        UUID id = UUID.randomUUID();
        InventoryItemSnapshot item = new InventoryItemSnapshot(id, "ammo", "1", 4, 30,
                2, 1, 3, 2, true, 8L, id, false, Map.of("name", "弹药"));
        assertEquals(id, item.instanceUuid());
        assertEquals(3, item.x());
        assertEquals(2, item.y());
        assertEquals(true, item.rotation());
    }
}
