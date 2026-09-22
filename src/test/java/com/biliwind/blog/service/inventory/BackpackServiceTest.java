package com.biliwind.blog.service.inventory;

import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserBackpackItem;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class BackpackServiceTest {
    @Inject BackpackService backpackService;
    @Inject ItemCatalog itemCatalog;

    @Test
    @TestTransaction
    void grantStacksAndIsIdempotent() {
        User user = User.find("order by id").firstResult();
        Assumptions.assumeTrue(user != null, "test database has no seeded user 1");
        StoreItem storeItem = testItem(true, 30, 1, 1);
        String key = UUID.randomUUID().toString();
        backpackService.grant(user.id, storeItem.itemCode, 31, GrantReason.CHECK_IN, key);
        assertEquals(2, UserBackpackItem.count("userId = ?1 and itemCode = ?2", user.id, storeItem.itemCode));
        backpackService.grant(user.id, storeItem.itemCode, 31, GrantReason.CHECK_IN, key);
        assertEquals(2, UserBackpackItem.count("userId = ?1 and itemCode = ?2", user.id, storeItem.itemCode));
    }

    @Test
    @TestTransaction
    void rejectsGrantWhenGridCannotFitAllInstances() {
        User user = User.find("order by id").firstResult();
        Assumptions.assumeTrue(user != null, "test database has no seeded user 1");
        StoreItem storeItem = testItem(false, 1, 10, 10);
        assertThrows(BadRequestException.class,
                () -> backpackService.grant(user.id, storeItem.itemCode, 2, GrantReason.CHECK_IN, UUID.randomUUID().toString()));
        assertEquals(0, UserBackpackItem.count("userId = ?1 and itemCode = ?2", user.id, storeItem.itemCode));
    }

    @Test
    @TestTransaction
    void grantsCompileTimeDefinitionWithoutStoreItem() {
        User user = User.find("order by id").firstResult();
        Assumptions.assumeTrue(user != null, "test database has no users");
        String code = "test-source-" + UUID.randomUUID();
        itemCatalog.register(new ItemDefinition() {
            @Override public String itemCode() { return code; }
            @Override public ItemMetadata metadata() {
                return new ItemMetadata("源物品", 1, 1, true, 5, null, null, java.util.Map.of());
            }
        });

        UserBackpackItem granted = backpackService.grant(user.id, code, 2,
                GrantReason.COMPENSATION, UUID.randomUUID().toString());
        assertNull(granted.storeItemId);
        assertEquals(code, granted.itemCode);
    }

    private StoreItem testItem(boolean stackable, int maxStack, int width, int height) {
        StoreItem item = new StoreItem();
        item.itemCode = "test-inventory-" + UUID.randomUUID();
        item.name = "测试背包物品";
        item.price = 0L;
        item.status = 1;
        item.stackable = stackable;
        item.maxStackSize = maxStack;
        item.width = width;
        item.height = height;
        item.persist();
        return item;
    }
}
