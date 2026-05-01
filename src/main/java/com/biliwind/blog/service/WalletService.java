package com.biliwind.blog.service;

import com.biliwind.blog.common.exception.ConcurrentModificationException;
import com.biliwind.blog.common.exception.InsufficientBalanceException;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserWallet;
import com.biliwind.blog.model.WalletTransaction;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * 钱包服务类
 * 提供积分查询、增加、扣减等方法，使用乐观锁确保并发安全
 */
@ApplicationScoped
public class WalletService {

    @Inject
    EntityManager entityManager;

    /**
     * 根据用户 ID 查询钱包
     *
     * @param userId 用户 ID
     * @return 用户钱包，不存在则返回 null
     */
    public UserWallet getWalletByUserId(Long userId) {
        return UserWallet.find("userId", userId).firstResult();
    }

    /**
     * 查询积分余额
     *
     * @param userId 用户 ID
     * @return 积分余额，不存在则返回 0
     */
    public Long getPointsBalance(Long userId) {
        UserWallet wallet = getWalletByUserId(userId);
        return wallet != null ? wallet.pointsBalance : 0L;
    }

    /**
     * 创建钱包
     *
     * @param userId 用户 ID
     * @return 创建的钱包
     */
    @Transactional
    public UserWallet createWallet(Long userId) {
        // 检查是否已存在
        UserWallet existing = getWalletByUserId(userId);
        if (existing != null) {
            // 补课：如果钱包已存在但用户表未关联，则进行关联
            User user = User.findById(userId);
            if (user != null && user.walletId == null) {
                user.walletId = existing.id;
                user.persist();
            }
            return existing;
        }

        UserWallet wallet = new UserWallet();
        wallet.userId = userId;
        wallet.pointsBalance = 0L;
        wallet.version = 0;
        wallet.persist();

        // 绑定到用户
        User user = User.findById(userId);
        if (user != null) {
            user.walletId = wallet.id;
            user.persist();
        }

        return wallet;
    }

    /**
     * 增加积分
     *
     * @param userId      用户 ID
     * @param points      积分数量
     * @param bizType     业务类型
     * @param description 描述信息
     * @return 增加后的余额
     */
    @Transactional
    public Long addPoints(Long userId, Long points, String bizType, String description) {
        return addPoints(userId, points, bizType, null, description);
    }

    /**
     * 增加积分（带业务 ID）
     *
     * @param userId      用户 ID
     * @param points      积分数量
     * @param bizType     业务类型
     * @param bizId       业务 ID
     * @param description 描述信息
     * @return 增加后的余额
     */
    @Transactional
    public Long addPoints(Long userId, Long points, String bizType, Long bizId, String description) {
        if (points <= 0) {
            throw new IllegalArgumentException("增加的积分必须大于 0");
        }

        UserWallet wallet = getWalletByUserId(userId);
        if (wallet == null) {
            wallet = createWallet(userId);
        }

        // 使用原生 SQL 进行乐观锁更新
        String sql = """
                UPDATE user_wallets 
                SET points_balance = points_balance + :points,
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = :id AND version = :version
                """;

        int updated = entityManager.createNativeQuery(sql)
                .setParameter("points", points)
                .setParameter("id", wallet.id)
                .setParameter("version", wallet.version)
                .executeUpdate();

        if (updated == 0) {
            throw new ConcurrentModificationException("钱包数据已被修改，请稍后重试");
        }

        // 刷新实体以获取最新数据
        entityManager.refresh(wallet);

        // 记录交易流水
        recordTransaction(wallet.id, userId, points, wallet.pointsBalance, bizType, bizId, description);

        return wallet.pointsBalance;
    }

    /**
     * 扣减积分
     *
     * @param userId      用户 ID
     * @param points      积分数量
     * @param bizType     业务类型
     * @param description 描述信息
     * @return 扣减后的余额
     * @throws InsufficientBalanceException    余额不足时抛出
     * @throws ConcurrentModificationException 并发冲突时抛出
     */
    @Transactional
    public Long deductPoints(Long userId, Long points, String bizType, String description) {
        return deductPoints(userId, points, bizType, null, description);
    }

    /**
     * 扣减积分（带业务 ID）
     *
     * @param userId      用户 ID
     * @param points      积分数量
     * @param bizType     业务类型
     * @param bizId       业务 ID
     * @param description 描述信息
     * @return 扣减后的余额
     * @throws InsufficientBalanceException    余额不足时抛出
     * @throws ConcurrentModificationException 并发冲突时抛出
     */
    @Transactional
    public Long deductPoints(Long userId, Long points, String bizType, Long bizId, String description) {
        if (points <= 0) {
            throw new IllegalArgumentException("扣减的积分必须大于 0");
        }

        UserWallet wallet = getWalletByUserId(userId);
        if (wallet == null) {
            throw new InsufficientBalanceException("钱包不存在");
        }

        if (wallet.pointsBalance < points) {
            throw new InsufficientBalanceException("积分余额不足");
        }

        // 使用原生 SQL 进行乐观锁更新
        String sql = """
                UPDATE user_wallets 
                SET points_balance = points_balance - :points,
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = :id 
                  AND points_balance >= :points
                  AND version = :version
                """;

        int updated = entityManager.createNativeQuery(sql)
                .setParameter("points", points)
                .setParameter("id", wallet.id)
                .setParameter("version", wallet.version)
                .executeUpdate();

        if (updated == 0) {
            // 再次检查余额
            entityManager.refresh(wallet);
            if (wallet.pointsBalance < points) {
                throw new InsufficientBalanceException("积分余额不足");
            }
            throw new ConcurrentModificationException("钱包数据已被修改，请稍后重试");
        }

        // 刷新实体以获取最新数据
        entityManager.refresh(wallet);

        // 记录交易流水
        recordTransaction(wallet.id, userId, -points, wallet.pointsBalance, bizType, bizId, description);

        return wallet.pointsBalance;
    }

    /**
     * 管理员调整积分（直接设置新余额）
     *
     * @param userId      用户 ID
     * @param newBalance  新余额
     * @param description 描述信息
     * @return 调整后的余额
     */
    @Transactional
    public Long adjustPoints(Long userId, Long newBalance, String description) {
        UserWallet wallet = getWalletByUserId(userId);
        if (wallet == null) {
            wallet = createWallet(userId);
        }

        Long oldBalance = wallet.pointsBalance;
        Long changeAmount = newBalance - oldBalance;

        // 直接更新余额
        wallet.pointsBalance = newBalance;
        wallet.version = wallet.version + 1;
        wallet.persist();

        // 记录交易流水
        recordTransaction(
                wallet.id,
                userId,
                changeAmount,
                newBalance,
                "ADMIN_ADJUST",
                null,
                description
        );

        return newBalance;
    }

    /**
     * 查询交易历史
     *
     * @param userId   用户 ID
     * @param page     页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 交易列表
     */
    public List<WalletTransaction> getTransactionHistory(Long userId, int page, int pageSize) {
        PanacheQuery<WalletTransaction> query = WalletTransaction.find(
                "userId",
                Sort.by("createdAt").descending(),
                userId
        );
        return query.page(Page.of(Math.max(page, 1) - 1, Math.max(pageSize, 1))).list();
    }

    /**
     * 查询交易总数
     *
     * @param userId 用户 ID
     * @return 交易总数
     */
    public long getTransactionCount(Long userId) {
        return WalletTransaction.count("userId", userId);
    }

    /**
     * 记录交易流水
     */
    @Transactional
    void recordTransaction(Long walletId, Long userId, Long changeAmount, Long balanceAfter,
                           String bizType, Long bizId, String description) {
        WalletTransaction transaction = new WalletTransaction();
        transaction.walletId = walletId;
        transaction.userId = userId;
        transaction.changeAmount = changeAmount;
        transaction.balanceAfter = balanceAfter;
        transaction.bizType = bizType;
        transaction.bizId = bizId;
        transaction.description = description;
        transaction.persist();
    }
}
