package com.biliwind.blog.service;

import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserCheckIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;

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
        if (hasCheckedInToday(userId)) {
            throw new IllegalStateException("今天已经签到过了");
        }

        // 1. 获取奖励配置
        JsonNode rewardConfig = getRewardConfig(userId);

        // 2. 发放奖励
        // 奖励类型 1: 积分
        if (rewardConfig.has("points")) {
            long points = rewardConfig.get("points").asLong();
            if (points > 0) {
                walletService.addPoints(userId, points, "CHECK_IN", "每日签到奖励");
            }
        }

        // 奖励类型 2: 经验 (后续扩展)
        if (rewardConfig.has("experience")) {
            long exp = rewardConfig.get("experience").asLong();
            if (exp > 0) {
                // TODO: 调用经验服务
                LOG.infof("用户 %d 获得经验: %d", userId, exp);
            }
        }

        // 奖励类型 3: 虚拟物品 (后续扩展)
        if (rewardConfig.has("items") && rewardConfig.get("items").isArray()) {
            JsonNode items = rewardConfig.get("items");
            // TODO: 调用背包/物品服务
        }

        // 3. 记录签到
        UserCheckIn checkIn = new UserCheckIn();
        checkIn.userId = userId;
        checkIn.checkInDate = LocalDate.now();
        checkIn.rewardInfo = rewardConfig;
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
