package com.biliwind.blog.service.inventory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemCatalogTest {
    @Test
    void rejectsDuplicateItemCodes() {
        ItemCatalog catalog = new ItemCatalog();
        ItemDefinition definition = definition("ammo_9mm");
        catalog.register(definition);
        assertThrows(IllegalStateException.class, () -> catalog.register(definition));
    }

    @Test
    void rejectsInvalidDimensionsAndStackLimits() {
        ItemCatalog catalog = new ItemCatalog();
        assertThrows(IllegalArgumentException.class, () -> catalog.register(new ItemDefinition() {
            public String itemCode() { return "bad"; }
            public ItemMetadata metadata() { return new ItemMetadata("bad", 0, 1, true, 1, null, null, Map.of()); }
        }));
        assertThrows(IllegalArgumentException.class, () -> catalog.register(new ItemDefinition() {
            public String itemCode() { return "bad-stack"; }
            public ItemMetadata metadata() { return new ItemMetadata("bad", 1, 1, false, 2, null, null, Map.of()); }
        }));
    }

    private ItemDefinition definition(String code) {
        return new ItemDefinition() {
            public String itemCode() { return code; }
            public ItemMetadata metadata() { return new ItemMetadata("测试物品", 2, 1, true, 30, null, null, Map.of()); }
        };
    }
}
