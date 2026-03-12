package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 用户钱包实体，对应 user_wallets
 * 极简设计：只存储 ID、用户 ID 和当前余额，方便加锁和更新
 */
@Entity
@Table(name = "user_wallets")
public class UserWallet extends PanacheEntityBase {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 用户 ID（外键关联 users.id，唯一）
     */
    @Column(name = "user_id", nullable = false, unique = true)
    public Long userId;

    /**
     * 积分余额（单位：分）
     */
    @Column(name = "points_balance", nullable = false)
    public Long pointsBalance;

    /**
     * 乐观锁版本号
     */
    @Column(nullable = false)
    public Integer version;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
