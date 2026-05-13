package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 钱包交易流水实体，对应 wallet_transactions
 * 记录每一笔积分变动
 */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction extends PanacheEntityBase {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 钱包 ID（外键关联 user_wallets.id）
     */
    @Column(name = "wallet_id", nullable = false)
    public Long walletId;

    /**
     * 用户 ID（冗余字段，方便查询）
     */
    @Column(name = "user_id", nullable = false)
    public Long userId;

    /**
     * 变动金额（正数为增加，负数为减少）
     */
    @Column(name = "change_amount", nullable = false)
    public Long changeAmount;

    /**
     * 变动后余额
     */
    @Column(name = "balance_after", nullable = false)
    public Long balanceAfter;

    /**
     * 业务类型（REGISTER, REWARD, PURCHASE, ADMIN_ADJUST 等）
     */
    @Column(name = "biz_type", nullable = false, length = 50)
    public String bizType;

    /**
     * 业务 ID（关联具体业务记录）
     */
    @Column(name = "biz_id")
    public Long bizId;

    /**
     * 描述信息
     */
    @Column(length = 512)
    public String description;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
