package com.biliwind.blog.service;

import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GamificationService {

    private static final Logger LOG = Logger.getLogger(GamificationService.class);

    @Inject
    WalletService walletService;

    @Inject
    ConfigManager configManager;

    // 简单公式计算达到某一等级所需的总经验：(level - 1) * level / 2 * 100
    // L1: 0, L2: 100, L3: 300, L4: 600, L5: 1000
    private int getRequiredExpForLevel(int level) {
        if (level < 1) return 0;
        return (level - 1) * level / 2 * 100;
    }

    /**
     * 增加用户经验并处理升级逻辑
     *
     * @param userId   用户ID
     * @param expToAdd 增加的经验值
     * @param source   经验来源描述（用于记录日志或业务逻辑）
     */
    @Transactional
    public void addExp(Long userId, int expToAdd, String source) {
        if (expToAdd <= 0) return;

        User user = User.findById(userId);
        if (user == null) {
            LOG.warn("Cannot add exp, user not found: " + userId);
            return;
        }

        user.exp += expToAdd;

        int currentLevel = user.level;
        int nextLevel = currentLevel + 1;

        boolean leveledUp = false;

        // 循环判断是否连续升级
        while (user.exp >= getRequiredExpForLevel(nextLevel)) {
            currentLevel = nextLevel;
            nextLevel++;
            leveledUp = true;
        }

        if (leveledUp) {
            int oldLevel = user.level;
            user.level = currentLevel;
            LOG.infof("User %d leveled up from %d to %d (Source: %s)", userId, oldLevel, currentLevel, source);

            // 发放升级奖励（从配置中读取，默认为每升一级奖励50积分）
            int rewardPerLevel = configManager.getInt("gamification", "level_up_reward", 50);
            int totalReward = rewardPerLevel * (currentLevel - oldLevel);

            if (totalReward > 0) {
                walletService.addPoints(userId, (long) totalReward, "LEVEL_UP", null,
                        "升级奖励 (从 Lv." + oldLevel + " 到 Lv." + currentLevel + ")");
            }
        }

        user.persist();
    }

    /**
     * 触发行为奖励（例如评论、签到等）
     */
    @Transactional
    public void triggerActionReward(Long userId, String actionType) {
        // actionType 例子：comment, checkin
        int expReward = configManager.getInt("gamification", actionType + "_exp_reward", 10);
        if (expReward > 0) {
            addExp(userId, expReward, actionType);
        }
    }
}
