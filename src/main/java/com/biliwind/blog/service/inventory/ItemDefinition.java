package com.biliwind.blog.service.inventory;

import java.util.Map;

/** Compile-time extension point for inventory items. */
public interface ItemDefinition {
    String itemCode();
    default String definitionVersion() { return "1"; }
    ItemMetadata metadata();
    default void validate(InventoryItemContext context) { }
    default UseResult use(InventoryItemContext context) { return UseResult.rejected("该物品暂不支持使用"); }
    default void onDeprecated(InventoryItemContext context) { }

    record ItemMetadata(String name, int width, int height, boolean stackable,
                        int maxStackSize, Integer containerRows, Integer containerColumns,
                        Map<String, Object> extraData) { }
    record UseResult(boolean success, String message) {
        public static UseResult accepted(String message) { return new UseResult(true, message); }
        public static UseResult rejected(String message) { return new UseResult(false, message); }
    }
}
