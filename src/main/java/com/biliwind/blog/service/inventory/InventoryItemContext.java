package com.biliwind.blog.service.inventory;

import com.biliwind.blog.model.UserBackpackItem;

public record InventoryItemContext(Long userId, UserBackpackItem item, UseRequest request) {
    public InventoryItemContext(Long userId, UserBackpackItem item) {
        this(userId, item, new UseRequest());
    }
}
