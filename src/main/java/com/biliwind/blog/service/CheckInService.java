package com.biliwind.blog.service;

import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserCheckIn;
import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.service.inventory.BackpackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 签到服务
 * 处理用户每日签到逻辑
 */
@ApplicationScoped
public class CheckInService {

    private static final Logger LOG = Logger.getLogger(CheckInService.class);

    @Inject
    ConfigManager configManager;

    @Inject
    WalletService walletService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    GamificationService gamificationService;

    @Inject
    BackpackService backpackService;

    /**
     * 检查用户今天是否已签到
     *
     * @param userId 用户 ID
     * @return 是否已签到
     */
    public boolean hasCheckedInToday(Long userId) {
        return UserCheckIn.findByUserAndDate(userId, LocalDate.now()) != null;
    }

    /**
     * 执行签到
     *
     * @param userId 用户 ID
     * @return 签到记录
     */
    @Transactional
    public UserCheckIn doCheckIn(Long userId) {
        LocalDate today = LocalDate.now();
        if (hasCheckedInToday(userId)) {
            throw new IllegalStateException("今天已经签到过了");
        }

        // 1. 获取奖励配置
        JsonNode rewardConfig = getRewardConfig(userId);

        List<JsonNode> itemRewards = new ArrayList<>();
        if (rewardConfig.has("items") && rewardConfig.get("items").isArray()) {
            for (JsonNode item : rewardConfig.get("items")) {
                long storeItemId = item.path("storeItemId").asLong(0);
                int quantity = Math.max(1, item.path("quantity").asInt(1));
                String code;
                if (storeItemId > 0) {
                    StoreItem storeItem = StoreItem.findById(storeItemId);
                    if (storeItem == null || storeItem.itemCode == null) throw new IllegalArgumentException("签到物品不存在");
                    code = storeItem.itemCode;
                } else if (item.hasNonNull("itemCode")) {
                    code = item.path("itemCode").asText();
                } else {
                    throw new IllegalArgumentException("签到物品缺少 storeItemId 或 itemCode");
                }
                backpackService.validateGrant(userId, code, quantity);
                StoreItem resolved = StoreItem.find("itemCode", code).firstResult();
                itemRewards.add(objectMapper.createObjectNode().put("itemCode", code).put("quantity", quantity)
                        .put("definitionVersion", resolved == null || resolved.definitionVersion == null ? "1" : resolved.definitionVersion)
                        .put("deprecated", resolved != null && resolved.deprecated));
            }
        }

        // 2. 发放奖励；容量已在积分/经验变更前完成整批预检
        // 奖励类型 1: 积分
        if (rewardConfig.has("points")) {
            long points = rewardConfig.get("points").asLong();
            if (points > 0) {
                walletService.addPoints(userId, points, "CHECK_IN", "每日签到奖励");
            }
        }

        // 奖励类型 2: 经验 (已实现)
        if (rewardConfig.has("experience")) {
            long exp = rewardConfig.get("experience").asLong();
            if (exp > 0) {
                gamificationService.addExp(userId, (int) exp, "CHECK_IN");
                LOG.infof("用户 %d 获得经验: %d", userId, exp);
            }
        }

        // 奖励类型 3: 虚拟物品
        if (rewardConfig.has("items") && rewardConfig.get("items").isArray()) {
            JsonNode items = rewardConfig.get("items");
            int rewardIndex = 0;
            for (JsonNode item : items) {
                long storeItemId = item.path("storeItemId").asLong(0);
                int quantity = Math.max(1, item.path("quantity").asInt(1));
                if (storeItemId > 0) {
                    backpackService.grant(userId, storeItemId, quantity, "CHECK_IN",
                            "CHECK_IN:" + userId + ":" + today + ":" + rewardIndex + ":" + storeItemId);
                } else if (item.hasNonNull("itemCode")) {
                    backpackService.grantByCode(userId, item.path("itemCode").asText(), null, quantity,
                            "CHECK_IN", "CHECK_IN:" + userId + ":" + today + ":" + rewardIndex + ":" + item.path("itemCode").asText());
                } else {
                    throw new IllegalArgumentException("签到物品缺少 storeItemId 或 itemCode");
                }
                rewardIndex++;
            }
        }

        // 3. 记录签到
        UserCheckIn checkIn = new UserCheckIn();
        checkIn.userId = userId;
        checkIn.checkInDate = today;
        ObjectNode actualReward = rewardConfig.deepCopy();
        actualReward.set("issuedItems", objectMapper.valueToTree(itemRewards));
        checkIn.rewardInfo = actualReward;
        checkIn.persist();

        LOG.infof("用户 %d 签到成功，获得奖励: %s", userId, rewardConfig.toString());
        return checkIn;
    }

    /**
     * 获取用户的奖励配置（优先用户私有配置，其次全局配置）
     */
    private JsonNode getRewardConfig(Long userId) {
        User user = User.findById(userId);

        // 检查用户是否有私有签到配置
        if (user != null && user.extraInfo != null) {
            JsonNode extraInfoNode = objectMapper.valueToTree(user.extraInfo);
            if (extraInfoNode.has("checkInReward")) {
                return extraInfoNode.get("checkInReward");
            }
        }

        // 获取全局配置
        JsonNode globalConfig = configManager.get("daily_check_in_rewards");
        if (globalConfig == null) {
            // 兜底配置
            ObjectNode defaultReward = objectMapper.createObjectNode();
            defaultReward.put("points", 10);
            return defaultReward;
        }

        return globalConfig;
    }
}
