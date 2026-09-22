package com.biliwind.blog.service.inventory;

import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.model.UserBackpackItem;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Compile-time item registry with a backwards-compatible store-item fallback. */
@ApplicationScoped
public class ItemCatalog {
    private final Map<String, ItemDefinition> definitions = new ConcurrentHashMap<>();

    @Inject
    Instance<ItemDefinition> discoveredDefinitions;

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        for (ItemDefinition definition : discoveredDefinitions) register(definition);
        syncDatabaseDefinitions();
    }

    public ItemDefinition find(String itemCode) {
        if (itemCode == null || itemCode.isBlank()) return null;
        ItemDefinition defined = definitions.get(itemCode);
        if (defined != null) return defined;
        StoreItem item = StoreItem.find("itemCode", itemCode).firstResult();
        return item == null ? null : new DatabaseItemDefinition(item);
    }

    public boolean isActive(String itemCode) {
        StoreItem item = StoreItem.find("itemCode", itemCode).firstResult();
        return find(itemCode) != null && (item == null || (item.status != 0 && !item.deprecated));
    }

    public Map<String, ItemDefinition.ItemMetadata> snapshotDefinitions() {
        Map<String, ItemDefinition.ItemMetadata> result = new LinkedHashMap<>();
        for (ItemDefinition definition : definitions.values()) result.put(definition.itemCode(), safeMetadata(definition.metadata()));
        for (StoreItem item : StoreItem.<StoreItem>listAll()) {
            if (item.itemCode != null && !item.deprecated && item.status != 0) {
                result.putIfAbsent(item.itemCode, safeMetadata(new DatabaseItemDefinition(item).metadata()));
            }
        }
        return result;
    }

    public void register(ItemDefinition definition) {
        validate(definition);
        if (definitions.putIfAbsent(definition.itemCode(), definition) != null) {
            throw new IllegalStateException("物品编码重复: " + definition.itemCode());
        }
    }

    private void validate(ItemDefinition definition) {
        if (definition == null || definition.itemCode() == null || definition.itemCode().isBlank()) {
            throw new IllegalArgumentException("物品定义编码不能为空");
        }
        ItemDefinition.ItemMetadata metadata = definition.metadata();
        if (metadata == null || metadata.width() <= 0 || metadata.height() <= 0
                || metadata.maxStackSize() <= 0
                || (!metadata.stackable() && metadata.maxStackSize() != 1)) {
            throw new IllegalArgumentException("物品定义尺寸或堆叠上限非法: " + definition.itemCode());
        }
        if ((metadata.containerRows() == null) != (metadata.containerColumns() == null)
                || (metadata.containerRows() != null
                && (metadata.containerRows() <= 0 || metadata.containerColumns() <= 0))) {
            throw new IllegalArgumentException("容器网格尺寸非法: " + definition.itemCode());
        }
    }

    private void syncDatabaseDefinitions() {
        if (definitions.isEmpty()) return;
        for (StoreItem item : StoreItem.<StoreItem>listAll()) {
            ItemDefinition definition = definitions.get(item.itemCode);
            item.deprecated = definition == null;
            if (definition != null) {
                item.definitionVersion = definition.definitionVersion();
                ItemDefinition.ItemMetadata m = definition.metadata();
                item.width = m.width(); item.height = m.height(); item.stackable = m.stackable();
                item.maxStackSize = m.maxStackSize(); item.containerRows = m.containerRows();
                item.containerColumns = m.containerColumns();
            }
        }
        for (UserBackpackItem item : UserBackpackItem.<UserBackpackItem>listAll()) {
            item.deprecated = !definitions.containsKey(item.itemCode)
                    && StoreItem.find("itemCode", item.itemCode).firstResult() == null;
        }
    }

    private ItemDefinition.ItemMetadata safeMetadata(ItemDefinition.ItemMetadata metadata) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (metadata.extraData() != null) {
            for (Map.Entry<String, Object> entry : metadata.extraData().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !Set.of("extraInfo", "password", "secret", "code", "url", "token", "key").contains(entry.getKey())) {
                    safe.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new ItemDefinition.ItemMetadata(metadata.name(), metadata.width(), metadata.height(), metadata.stackable(),
                metadata.maxStackSize(), metadata.containerRows(), metadata.containerColumns(), safe);
    }

    private record DatabaseItemDefinition(StoreItem item) implements ItemDefinition {
        public String itemCode() { return item.itemCode; }
        public String definitionVersion() { return item.definitionVersion == null ? "1" : item.definitionVersion; }
        public ItemMetadata metadata() {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("storeItemId", item.id); safe.put("name", item.name);
            safe.put("rarity", item.rarity); safe.put("type", item.type);
            return new ItemMetadata(item.name, value(item.width), value(item.height), item.stackable,
                    value(item.maxStackSize), item.containerRows, item.containerColumns, safe);
        }
        private static int value(Integer value) { return value == null ? 1 : value; }
    }
}
