package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 用户背包物品实体，对应 user_backpack_items
 */
@Entity
@Table(name = "user_backpack_items")
public class UserBackpackItem extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    public Long userId;

    /**
     * 商店物品 ID
     */
    @Column(name = "store_item_id", nullable = false)
    public Long storeItemId;

    /**
     * 获取时间
     */
    @Column(name = "acquired_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime acquiredAt;
}
