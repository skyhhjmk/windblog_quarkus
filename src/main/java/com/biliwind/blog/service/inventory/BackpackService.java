package com.biliwind.blog.service.inventory;

import com.biliwind.blog.model.InventoryContainer;
import com.biliwind.blog.model.InventoryOperation;
import com.biliwind.blog.model.InventoryWarehouse;
import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserBackpackItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The single transactional boundary for all inventory mutations. */
@ApplicationScoped
public class BackpackService {
    @ConfigProperty(name = "inventory.root-columns", defaultValue = "10")
    int rootColumns = 10;
    @ConfigProperty(name = "inventory.root-rows", defaultValue = "10")
    int rootRows = 10;
    @ConfigProperty(name = "inventory.max-nesting-depth", defaultValue = "4")
    int maxNestingDepth = 4;

    @Inject EntityManager entityManager;
    @Inject ItemCatalog itemCatalog;

    @Transactional
    public InventorySnapshot getSnapshot(Long userId) {
        return buildSnapshot(userId, currentRevision(userId));
    }

    @Transactional
    public UserBackpackItem grant(Long userId, Long storeItemId, int quantity, String reason, String idempotencyKey) {
        StoreItem item = StoreItem.findById(storeItemId);
        if (item == null) throw new BadRequestException("物品不存在");
        return grantByCode(userId, item.itemCode == null ? "store:" + item.id : item.itemCode,
                storeItemId, quantity, reason, idempotencyKey);
    }

    @Transactional
    public UserBackpackItem grant(Long userId, String itemCode, int quantity,
                                  GrantReason reason, String idempotencyKey) {
        return grantByCode(userId, itemCode, null, quantity,
                reason == null ? null : reason.name(), idempotencyKey);
    }

    @Transactional
    public UserBackpackItem grantByCode(Long userId, String itemCode, Long storeItemId,
                                        int quantity, String reason, String idempotencyKey) {
        if (quantity < 1) throw new BadRequestException("物品数量必须大于 0");
        if (User.findById(userId) == null) throw new BadRequestException("用户不存在");
        String key = normalizeKey(idempotencyKey);
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        InventoryOperation previous = findOperation(userId, key);
        if (previous != null) return findByUuid(userId, previous.resultInstanceUuid);
        StoreItem storeItem = storeItemId == null
                ? StoreItem.find("itemCode", itemCode).firstResult() : StoreItem.findById(storeItemId);
        if (storeItemId != null && storeItem == null) {
            throw new BadRequestException("物品不存在、已下架或已弃用");
        }
        if (storeItem != null && (storeItem.status == 0 || storeItem.deprecated)) {
            throw new BadRequestException("物品不存在、已下架或已弃用");
        }
        if ((itemCode == null || itemCode.isBlank()) && storeItem != null) itemCode = storeItem.itemCode;
        if (itemCode == null || itemCode.isBlank()) throw new BadRequestException("物品编码不能为空");
        if (storeItem != null && storeItem.itemCode == null) storeItem.itemCode = itemCode;
        ItemDefinition definition = itemCatalog.find(itemCode);
        if (definition == null || !itemCatalog.isActive(itemCode)) {
            throw new BadRequestException("物品定义不存在或已弃用");
        }
        ItemDefinition.ItemMetadata metadata = definition.metadata();
        validateMetadata(metadata, itemCode);
        List<UserBackpackItem> items = itemsForUser(userId);
        int newStacks = quantityToNewStacks(items, itemCode, metadata, quantity);
        List<int[]> positions = planNewPositions(items, rootContainer(warehouse).id,
                metadata.width(), metadata.height(), newStacks);
        int remaining = quantity;
        UserBackpackItem first = null;
        if (metadata.stackable()) {
            for (UserBackpackItem stack : items) {
                if (itemCode.equals(stack.itemCode) && !stack.deprecated && stack.quantity < stack.maxStackSize
                        && remaining > 0) {
                    int add = Math.min(remaining, stack.maxStackSize - stack.quantity);
                    stack.quantity += add; remaining -= add;
                    if (first == null) first = stack;
                }
            }
        }
        int positionIndex = 0;
        while (remaining > 0) {
            int stackSize = metadata.stackable() ? Math.min(remaining, metadata.maxStackSize()) : 1;
            int[] position = positions.get(positionIndex++);
            UserBackpackItem created = new UserBackpackItem();
            created.instanceUuid = UUID.randomUUID(); created.userId = userId;
            created.storeItemId = storeItem == null ? null : storeItem.id; created.itemCode = itemCode;
            created.definitionVersion = definition.definitionVersion(); created.quantity = stackSize;
            created.maxStackSize = metadata.maxStackSize(); created.width = metadata.width();
            created.height = metadata.height(); created.posX = position[0]; created.posY = position[1];
            created.containerId = rootContainer(warehouse).id; created.deprecated = false;
            created.definitionSnapshot = safeMetadata(metadata, definition.definitionVersion());
            created.persist(); createNestedContainerIfNeeded(created, metadata, userId);
            if (first == null) first = created;
            remaining -= stackSize;
        }
        touch(warehouse, userId);
        String operationType = "PURCHASE".equalsIgnoreCase(reason) ? "PURCHASE"
                : "CHECK_IN".equalsIgnoreCase(reason) ? "CHECK_IN" : "GRANT";
        recordOperation(userId, key, operationType, warehouse.revision, first == null ? null : first.instanceUuid,
                Map.of("reason", reason == null ? "" : reason, "quantity", quantity));
        return first;
    }

    @Transactional
    public void validateGrant(Long userId, String itemCode, int quantity) {
        if (quantity < 1) throw new BadRequestException("物品数量必须大于 0");
        StoreItem item = StoreItem.find("itemCode", itemCode).firstResult();
        if (item != null && (item.status == 0 || item.deprecated)) throw new BadRequestException("物品不存在或已弃用");
        ItemDefinition definition = itemCatalog.find(itemCode);
        if (definition == null || !itemCatalog.isActive(itemCode)) throw new BadRequestException("物品定义不存在或已弃用");
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        ItemDefinition.ItemMetadata metadata = definition.metadata();
        int newStacks = quantityToNewStacks(itemsForUser(userId), itemCode, metadata, quantity);
        planNewPositions(itemsForUser(userId), rootContainer(warehouse).id,
                metadata.width(), metadata.height(), newStacks);
    }

    @Transactional
    public boolean isProcessed(Long userId, String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank() && findOperation(userId, idempotencyKey) != null;
    }

    @Transactional
    public ItemDefinition.UseResult use(Long userId, UUID instanceUuid, UseRequest request, String idempotencyKey) {
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        String key = normalizeKey(idempotencyKey);
        if (findOperation(userId, key) != null) return ItemDefinition.UseResult.accepted("请求已处理");
        UserBackpackItem item = findByUuid(userId, instanceUuid);
        if (item == null) return ItemDefinition.UseResult.rejected("物品不存在");
        ItemDefinition definition = itemCatalog.find(item.itemCode);
        if (definition == null) return ItemDefinition.UseResult.rejected("物品定义已弃用");
        UseRequest actualRequest = request == null ? new UseRequest() : request;
        ItemDefinition.UseResult result;
        if (item.deprecated) {
            definition.onDeprecated(new InventoryItemContext(userId, item, actualRequest));
            result = ItemDefinition.UseResult.rejected("物品已弃用，只允许移动或删除");
        } else {
            InventoryItemContext context = new InventoryItemContext(userId, item, actualRequest);
            definition.validate(context);
            result = definition.use(context);
        }
        if (!result.success()) return result;
        if (item.quantity > 1) item.quantity--; else item.delete();
        touch(warehouse, userId);
        recordOperation(userId, key, "USE", warehouse.revision, instanceUuid,
                Map.of("message", result.message() == null ? "" : result.message()));
        return result;
    }

    public ItemDefinition.UseResult use(Long userId, UUID instanceUuid, String idempotencyKey) {
        return use(userId, instanceUuid, new UseRequest(), idempotencyKey);
    }

    @Transactional
    public void move(Long userId, UUID instanceUuid, int x, int y, Long containerId,
                     UUID parentInstanceUuid, String idempotencyKey) {
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        String key = normalizeKey(idempotencyKey); if (findOperation(userId, key) != null) return;
        UserBackpackItem item = requireItem(userId, instanceUuid);
        item.posX = x; item.posY = y; item.containerId = containerId; item.parentInstanceUuid = parentInstanceUuid;
        validateStoredLayout(userId); touch(warehouse, userId);
        recordOperation(userId, key, "MOVE", warehouse.revision, instanceUuid, Map.of());
    }

    @Transactional
    public void rotate(Long userId, UUID instanceUuid, String idempotencyKey) {
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        String key = normalizeKey(idempotencyKey); if (findOperation(userId, key) != null) return;
        UserBackpackItem item = requireItem(userId, instanceUuid); item.rotated = !item.rotated;
        validateStoredLayout(userId); touch(warehouse, userId);
        recordOperation(userId, key, "ROTATE", warehouse.revision, instanceUuid, Map.of());
    }

    @Transactional
    public UserBackpackItem split(Long userId, UUID instanceUuid, int quantity, String idempotencyKey) {
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        String key = normalizeKey(idempotencyKey); InventoryOperation previous = findOperation(userId, key);
        if (previous != null) return findByUuid(userId, previous.resultInstanceUuid);
        UserBackpackItem source = requireItem(userId, instanceUuid);
        ItemDefinition definition = itemCatalog.find(source.itemCode);
        if (source.quantity <= 1 || quantity <= 0 || quantity >= source.quantity
                || definition == null || !definition.metadata().stackable()) throw new BadRequestException("拆分数量非法");
        List<UserBackpackItem> items = itemsForUser(userId);
        int[] position = findFreePosition(items, source.containerId, source.width, source.height, source.rotated);
        source.quantity -= quantity; UserBackpackItem split = copyInstance(source);
        split.instanceUuid = UUID.randomUUID(); split.quantity = quantity; split.posX = position[0]; split.posY = position[1];
        split.persist(); touch(warehouse, userId);
        recordOperation(userId, key, "SPLIT", warehouse.revision, split.instanceUuid,
                Map.of("source", instanceUuid.toString(), "quantity", quantity));
        return split;
    }

    @Transactional
    public void merge(Long userId, UUID targetUuid, UUID sourceUuid, String idempotencyKey) {
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        String key = normalizeKey(idempotencyKey); if (findOperation(userId, key) != null) return;
        UserBackpackItem target = requireItem(userId, targetUuid), source = requireItem(userId, sourceUuid);
        if (target == source || !java.util.Objects.equals(target.itemCode, source.itemCode)
                || !java.util.Objects.equals(target.containerId, source.containerId)
                || target.quantity + source.quantity > target.maxStackSize) throw new BadRequestException("物品不能合并");
        target.quantity += source.quantity; source.delete(); touch(warehouse, userId);
        recordOperation(userId, key, "MERGE", warehouse.revision, targetUuid, Map.of("source", sourceUuid.toString()));
    }

    @Transactional
    public InventorySnapshot applySnapshot(Long userId, long baseRevision,
                                           InventorySnapshot submitted, String operationId) {
        InventoryWarehouse warehouse = warehouseForUpdate(userId);
        String key = normalizeKey(operationId);
        if (findOperation(userId, key) != null) return getSnapshot(userId);
        if (warehouse.revision != baseRevision) throw new ConflictException(getSnapshot(userId));
        if (submitted == null || submitted.items() == null) throw new BadRequestException("仓库快照不能为空");
        List<UserBackpackItem> current = itemsForUser(userId); validateSubmitted(userId, current, submitted.items());
        Map<UUID, InventoryItemSnapshot> incoming = new HashMap<>();
        for (InventoryItemSnapshot value : submitted.items()) incoming.put(value.instanceUuid(), value);
        for (UserBackpackItem item : current) {
            InventoryItemSnapshot value = incoming.get(item.instanceUuid);
            if (value == null) item.delete();
            else { item.posX = value.x(); item.posY = value.y(); item.rotated = value.rotation();
                item.containerId = value.containerId(); item.parentInstanceUuid = value.parentInstanceUuid(); }
        }
        validateStoredLayout(userId); touch(warehouse, userId);
        recordOperation(userId, key, "SNAPSHOT", warehouse.revision, null, Map.of("baseRevision", baseRevision));
        return getSnapshot(userId);
    }

    public InventorySnapshot applySnapshot(Long userId, long baseRevision, InventorySnapshot submitted) {
        return applySnapshot(userId, baseRevision, submitted, UUID.randomUUID().toString());
    }

    private void validateSubmitted(Long userId, List<UserBackpackItem> current, List<InventoryItemSnapshot> incoming) {
        Map<UUID, UserBackpackItem> known = new HashMap<>(); for (UserBackpackItem item : current) known.put(item.instanceUuid, item);
        Set<UUID> ids = new HashSet<>();
        for (InventoryItemSnapshot value : incoming) {
            if (value == null || value.instanceUuid() == null || !ids.add(value.instanceUuid())) throw new BadRequestException("仓库快照包含重复或空实例");
            UserBackpackItem stored = known.get(value.instanceUuid());
            if (stored == null) throw new BadRequestException("不能创建未知物品实例");
            if (value.quantity() != stored.quantity || !java.util.Objects.equals(value.itemCode(), stored.itemCode)
                    || value.width() != stored.width || value.height() != stored.height || value.maxStackSize() != stored.maxStackSize) {
                throw new BadRequestException("仓库快照包含不可修改字段");
            }
        }
        validateLayout(userId, incoming);
    }

    private void validateStoredLayout(Long userId) {
        List<UserBackpackItem> items = itemsForUser(userId); List<InventoryItemSnapshot> values = new ArrayList<>();
        for (UserBackpackItem item : items) values.add(toSnapshot(item)); validateLayout(userId, values);
    }

    private void validateLayout(Long userId, List<InventoryItemSnapshot> items) {
        Map<Long, InventoryContainer> containers = new HashMap<>();
        for (InventoryContainer container : InventoryContainer.<InventoryContainer>list("userId", userId)) containers.put(container.id, container);
        Map<UUID, InventoryItemSnapshot> byUuid = new HashMap<>(); for (InventoryItemSnapshot item : items) byUuid.put(item.instanceUuid(), item);
        for (InventoryItemSnapshot item : items) {
            if (item.quantity() < 1 || item.width() <= 0 || item.height() <= 0) throw new BadRequestException("物品数量或尺寸非法");
            int w = item.rotation() ? item.height() : item.width(), h = item.rotation() ? item.width() : item.height();
            if (item.containerId() == null) {
                if (item.parentInstanceUuid() != null) throw new BadRequestException("根仓库物品不能有父物品");
                if (item.x() < 0 || item.y() < 0 || item.x() + w > rootColumns || item.y() + h > rootRows) throw new BadRequestException("物品超出仓库边界");
            } else {
                InventoryContainer container = containers.get(item.containerId());
                if (container == null || (container.parentItemUuid != null && !container.parentItemUuid.equals(item.parentInstanceUuid()))) throw new BadRequestException("容器关系非法");
                if (container.parentItemUuid == null && item.parentInstanceUuid() != null) throw new BadRequestException("根仓库不能有父物品");
                if (item.x() < 0 || item.y() < 0 || item.x() + w > container.columns || item.y() + h > container.rows) throw new BadRequestException("物品超出容器边界");
            }
            if (item.parentInstanceUuid() != null) {
                InventoryItemSnapshot parent = byUuid.get(item.parentInstanceUuid());
                if (parent == null || !isContainer(parent)) throw new BadRequestException("父物品不是容器");
                if (depth(item, byUuid) > maxNestingDepth) throw new BadRequestException("超过最大嵌套深度");
            }
        }
        for (int i = 0; i < items.size(); i++) for (int j = i + 1; j < items.size(); j++) {
            InventoryItemSnapshot a = items.get(i), b = items.get(j); if (sameLayoutContainer(a.containerId(), b.containerId()) && overlaps(a, b)) throw new BadRequestException("物品位置重叠");
        }
    }

    private int depth(InventoryItemSnapshot item, Map<UUID, InventoryItemSnapshot> all) {
        int depth = 0; Set<UUID> seen = new HashSet<>(); UUID parent = item.parentInstanceUuid();
        while (parent != null) { if (!seen.add(parent)) throw new BadRequestException("容器关系形成循环"); InventoryItemSnapshot value = all.get(parent); if (value == null) throw new BadRequestException("父物品不存在"); depth++; parent = value.parentInstanceUuid(); }
        return depth;
    }

    private boolean isContainer(InventoryItemSnapshot item) { ItemDefinition d = itemCatalog.find(item.itemCode()); return d != null && d.metadata().containerRows() != null; }
    private boolean overlaps(InventoryItemSnapshot a, InventoryItemSnapshot b) {
        int aw = a.rotation() ? a.height() : a.width(), ah = a.rotation() ? a.width() : a.height(); int bw = b.rotation() ? b.height() : b.width(), bh = b.rotation() ? b.width() : b.height();
        return a.x() < b.x() + bw && a.x() + aw > b.x() && a.y() < b.y() + bh && a.y() + ah > b.y();
    }

    private List<int[]> planNewPositions(List<UserBackpackItem> items, Long containerId, int width, int height, int count) {
        List<int[]> result = new ArrayList<>(); List<Box> occupied = new ArrayList<>();
        for (UserBackpackItem item : items) if (sameContainer(item.containerId, containerId)) occupied.add(box(item));
        int columns = rootColumns, rows = rootRows; if (containerId != null) { InventoryContainer c = InventoryContainer.findById(containerId); if (c == null) throw new BadRequestException("容器不存在"); columns = c.columns; rows = c.rows; }
        for (int n = 0; n < count; n++) { int[] found = null; for (int y = 0; y <= rows - height && found == null; y++) for (int x = 0; x <= columns - width; x++) { Box candidate = new Box(containerId, x, y, width, height); boolean free = true; for (Box value : occupied) if (overlaps(candidate, value)) { free = false; break; } if (free) { found = new int[]{x, y}; break; } } if (found == null) throw new BadRequestException("背包空间不足"); result.add(found); occupied.add(new Box(containerId, found[0], found[1], width, height)); }
        return result;
    }
    private int[] findFreePosition(List<UserBackpackItem> items, Long containerId, int width, int height, boolean rotated) { return planNewPositions(items, containerId, rotated ? height : width, rotated ? width : height, 1).get(0); }
    private int quantityToNewStacks(List<UserBackpackItem> items, String code, ItemDefinition.ItemMetadata m, int quantity) { int remaining = quantity; if (m.stackable()) for (UserBackpackItem item : items) if (code.equals(item.itemCode) && !item.deprecated && item.quantity < item.maxStackSize) { remaining -= Math.min(remaining, item.maxStackSize - item.quantity); if (remaining == 0) return 0; } return m.stackable() ? (remaining + m.maxStackSize() - 1) / m.maxStackSize() : quantity; }

    private InventoryWarehouse warehouseForUpdate(Long userId) {
        entityManager.createNativeQuery("insert into inventory_warehouses(user_id, revision, updated_at) values (:userId, 0, current_timestamp) on conflict (user_id) do nothing").setParameter("userId", userId).executeUpdate();
        InventoryWarehouse warehouse = InventoryWarehouse.findById(userId, LockModeType.PESSIMISTIC_WRITE); if (warehouse == null) throw new BadRequestException("仓库初始化失败"); rootContainer(warehouse); return warehouse;
    }
    private InventoryContainer rootContainer(InventoryWarehouse warehouse) { InventoryContainer root = InventoryContainer.find("userId = ?1 and parentItemUuid is null", warehouse.userId).firstResult(); if (root == null) { root = new InventoryContainer(); root.userId = warehouse.userId; root.rows = rootRows; root.columns = rootColumns; root.revision = warehouse.revision; root.updatedAt = OffsetDateTime.now(); root.persist(); } return root; }
    private void createNestedContainerIfNeeded(UserBackpackItem item, ItemDefinition.ItemMetadata m, Long userId) { if (m.containerRows() == null) return; InventoryContainer c = new InventoryContainer(); c.userId = userId; c.parentItemUuid = item.instanceUuid; c.rows = m.containerRows(); c.columns = m.containerColumns(); c.revision = 0L; c.updatedAt = OffsetDateTime.now(); c.persist(); }
    private void touch(InventoryWarehouse warehouse, Long userId) { warehouse.revision++; warehouse.updatedAt = OffsetDateTime.now(); for (InventoryContainer c : InventoryContainer.<InventoryContainer>list("userId", userId)) { c.revision = warehouse.revision; c.updatedAt = OffsetDateTime.now(); } warehouse.snapshot = buildSnapshot(userId, warehouse.revision); warehouse.persist(); for (InventoryContainer c : InventoryContainer.<InventoryContainer>list("userId", userId)) c.snapshot = warehouse.snapshot; }
    private void recordOperation(Long userId, String key, String type, long revision, UUID uuid, Object result) { InventoryOperation op = new InventoryOperation(); op.operationId = parseUuid(key); op.userId = userId; op.idempotencyKey = key; op.operationType = type; op.requestRevision = revision; op.resultInstanceUuid = uuid; op.result = result; op.createdAt = OffsetDateTime.now(); op.persist(); }
    private InventoryOperation findOperation(Long userId, String key) { return InventoryOperation.find("userId = ?1 and idempotencyKey = ?2", userId, key).firstResult(); }
    private String normalizeKey(String key) { return key == null || key.isBlank() ? UUID.randomUUID().toString() : key; }
    private UUID parseUuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException ignored) { return UUID.randomUUID(); } }
    private List<UserBackpackItem> itemsForUser(Long userId) { return UserBackpackItem.list("userId = ?1 order by acquiredAt asc, id asc", userId); }
    private UserBackpackItem requireItem(Long userId, UUID uuid) { UserBackpackItem item = findByUuid(userId, uuid); if (item == null) throw new BadRequestException("物品实例不存在"); return item; }
    private UserBackpackItem findByUuid(Long userId, UUID uuid) { return uuid == null ? null : UserBackpackItem.find("userId = ?1 and instanceUuid = ?2", userId, uuid).firstResult(); }
    private UserBackpackItem copyInstance(UserBackpackItem s) { UserBackpackItem c = new UserBackpackItem(); c.userId = s.userId; c.storeItemId = s.storeItemId; c.itemCode = s.itemCode; c.definitionVersion = s.definitionVersion; c.maxStackSize = s.maxStackSize; c.width = s.width; c.height = s.height; c.rotated = s.rotated; c.containerId = s.containerId; c.parentInstanceUuid = s.parentInstanceUuid; c.deprecated = s.deprecated; c.definitionSnapshot = s.definitionSnapshot; c.extraData = s.extraData; return c; }
    private InventoryItemSnapshot toSnapshot(UserBackpackItem i) { return new InventoryItemSnapshot(i.instanceUuid, i.itemCode, i.definitionVersion, i.quantity, i.maxStackSize, i.width, i.height, i.posX, i.posY, i.rotated, i.containerId, i.parentInstanceUuid, i.deprecated, safeSnapshotMetadata(i.definitionSnapshot)); }
    private InventorySnapshot buildSnapshot(Long userId, long revision) { List<InventoryItemSnapshot> items = new ArrayList<>(); for (UserBackpackItem i : itemsForUser(userId)) items.add(toSnapshot(i)); List<InventoryContainerSnapshot> containers = new ArrayList<>(); for (InventoryContainer c : InventoryContainer.<InventoryContainer>list("userId", userId)) containers.add(new InventoryContainerSnapshot(c.id, c.userId, c.parentItemUuid, c.rows, c.columns, c.revision)); return new InventorySnapshot(revision, items, containers, itemCatalog.snapshotDefinitions()); }
    private long currentRevision(Long userId) { InventoryWarehouse w = InventoryWarehouse.findById(userId); return w == null ? 0L : w.revision; }
    private Map<String, Object> safeMetadata(ItemDefinition.ItemMetadata m, String version) { Map<String, Object> value = new LinkedHashMap<>(); value.put("name", m.name()); value.put("width", m.width()); value.put("height", m.height()); value.put("stackable", m.stackable()); value.put("maxStackSize", m.maxStackSize()); value.put("definitionVersion", version); return value; }
    private Map<String, Object> safeSnapshotMetadata(Object value) { if (!(value instanceof Map<?, ?> source)) return Map.of(); Map<String, Object> result = new LinkedHashMap<>(); for (Map.Entry<?, ?> entry : source.entrySet()) if (entry.getKey() != null && entry.getValue() != null && !Set.of("extraInfo", "password", "secret", "code", "url", "idempotencyKey", "reason").contains(entry.getKey().toString())) result.put(entry.getKey().toString(), entry.getValue()); return result; }
    private void validateMetadata(ItemDefinition.ItemMetadata m, String code) { if (m == null || m.width() <= 0 || m.height() <= 0 || m.maxStackSize() <= 0 || (!m.stackable() && m.maxStackSize() != 1)) throw new BadRequestException("物品定义非法: " + code); }
    private Box box(UserBackpackItem i) { return new Box(i.containerId, i.posX, i.posY, i.rotated ? i.height : i.width, i.rotated ? i.width : i.height); }
    private record Box(Long containerId, int x, int y, int width, int height) { }
    private boolean overlaps(Box a, Box b) { return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y; }
    private boolean sameContainer(Long itemContainerId, Long targetContainerId) {
        if (java.util.Objects.equals(itemContainerId, targetContainerId)) return true;
        if (targetContainerId == null || itemContainerId != null) return false;
        InventoryContainer target = InventoryContainer.findById(targetContainerId);
        return target != null && target.parentItemUuid == null;
    }
    private boolean sameLayoutContainer(Long first, Long second) {
        if (java.util.Objects.equals(first, second)) return true;
        if (first == null || second == null) {
            Long nonNull = first == null ? second : first;
            InventoryContainer container = nonNull == null ? null : InventoryContainer.findById(nonNull);
            return container != null && container.parentItemUuid == null;
        }
        InventoryContainer a = InventoryContainer.findById(first), b = InventoryContainer.findById(second);
        return a != null && b != null && a.parentItemUuid == null && b.parentItemUuid == null;
    }

    public static class ConflictException extends RuntimeException {
        private final InventorySnapshot current;
        public ConflictException(InventorySnapshot current) { super("仓库版本已变化"); this.current = current; }
        public InventorySnapshot current() { return current; }
        public long currentRevision() { return current.revision(); }
    }
}
