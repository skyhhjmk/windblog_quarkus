package com.biliwind.blog.service;

import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserBackpackItem;
import com.biliwind.blog.model.UserPurchaseRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import com.biliwind.blog.service.inventory.BackpackService;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class StoreService {

    private static final Logger LOG = Logger.getLogger(StoreService.class);

    @Inject
    WalletService walletService;

    @Inject
    BackpackService backpackService;

    /**
     * 购买商店物品
     *
     * @param userId      用户ID
     * @param storeItemId 商品ID
     */
    @Transactional
    public void buyStoreItem(Long userId, Long storeItemId) {
        buyStoreItem(userId, storeItemId, UUID.randomUUID().toString());
    }

    @Transactional
    public void buyStoreItem(Long userId, Long storeItemId, String idempotencyKey) {
        if (backpackService.isProcessed(userId, idempotencyKey)) {
            return;
        }
        User user = User.findById(userId);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }

        StoreItem item = StoreItem.findById(storeItemId);
        if (item == null || item.status == 0) {
            throw new BadRequestException("商品不存在或已下架");
        }

        // 检查是否已经购买过此物品（假设大部分商品为买断制，或可重复购买？这里限制每种只能买一个）
        long existingCount = UserBackpackItem.count("userId = ?1 and storeItemId = ?2", userId, storeItemId);
        if (!item.stackable && existingCount > 0) {
            throw new BadRequestException("您已经拥有此物品");
        }

        // 在扣积分前验证网格空间；BackpackService 会在同一事务中再次锁定并提交
        String itemCode = item.itemCode == null ? "store:" + item.id : item.itemCode;
        backpackService.validateGrant(userId, itemCode, 1);
        // 扣除积分
        walletService.deductPoints(userId, item.price, "BUY_STORE_ITEM", storeItemId, "购买商店物品: " + item.name);

        // 添加到背包（统一处理堆叠、网格位置和实例 UUID）
        backpackService.grant(userId, storeItemId, 1, "PURCHASE", idempotencyKey);

        // 记录购买流水
        UserPurchaseRecord record = new UserPurchaseRecord();
        record.userId = userId;
        record.targetType = "STORE_ITEM";
        record.targetId = storeItemId;
        record.pointsPaid = item.price;
        record.persist();

        LOG.infof("User %d bought store item %d for %d points", userId, storeItemId, item.price);
    }

    /**
     * 获取用户背包物品
     */
    public List<UserBackpackItem> getUserBackpack(Long userId) {
        return UserBackpackItem.list("userId = ?1 order by acquiredAt asc", userId);
    }

    /**
     * 检查用户是否拥有指定物品
     */
    public boolean hasStoreItem(Long userId, Long storeItemId) {
        return UserBackpackItem.count("userId = ?1 and storeItemId = ?2", userId, storeItemId) > 0;
    }
}
