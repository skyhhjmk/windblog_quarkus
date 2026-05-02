package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 用户购买记录实体，对应 user_purchase_records
 */
@Entity
@Table(name = "user_purchase_records")
public class UserPurchaseRecord extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    public Long userId;

    /**
     * 购买目标类型：POST（文章）或 STORE_ITEM（商品）
     */
    @Column(name = "target_type", nullable = false, length = 32)
    public String targetType;

    /**
     * 购买目标 ID
     */
    @Column(name = "target_id", nullable = false)
    public Long targetId;

    /**
     * 购买目标区块 ID（用于文章内部分区块解锁）
     */
    @Column(name = "target_block_id", length = 64)
    public String targetBlockId;

    /**
     * 支付积分数量
     */
    @Column(name = "points_paid", nullable = false)
    public Long pointsPaid;

    /**
     * 购买时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
