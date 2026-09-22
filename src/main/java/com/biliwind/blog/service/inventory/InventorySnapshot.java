package com.biliwind.blog.service.inventory;

import java.util.List;
import java.util.Map;

public record InventorySnapshot(long revision,
                                List<InventoryItemSnapshot> items,
                                List<InventoryContainerSnapshot> containers,
                                Map<String, ItemDefinition.ItemMetadata> definitions) {
    public InventorySnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        containers = containers == null ? List.of() : List.copyOf(containers);
        definitions = definitions == null ? Map.of() : Map.copyOf(definitions);
    }
}
