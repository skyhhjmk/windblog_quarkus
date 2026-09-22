package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户背包物品实体，对应 user_backpack_items
 */
@Entity
@Table(name = "user_backpack_items")
public class UserBackpackItem extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "instance_uuid", nullable = false, unique = true)
    public UUID instanceUuid;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    public Long userId;

    /**
     * 商店物品 ID
     */
    @Column(name = "store_item_id")
    public Long storeItemId;

    @Column(name = "item_code", length = 128)
    public String itemCode;

    @Column(name = "definition_version", length = 64, nullable = false)
    public String definitionVersion = "1";

    @Column(nullable = false)
    public Integer quantity = 1;

    @Column(name = "pos_x", nullable = false)
    public Integer posX = 0;

    @Column(name = "pos_y", nullable = false)
    public Integer posY = 0;

    @Column(nullable = false)
    public boolean rotated = false;

    @Column(nullable = false)
    public Integer width = 1;

    @Column(nullable = false)
    public Integer height = 1;

    @Column(name = "max_stack_size", nullable = false)
    public Integer maxStackSize = 1;

    @Column(name = "container_id")
    public Long containerId;

    @Column(name = "parent_instance_uuid")
    public UUID parentInstanceUuid;

    @Column(name = "deprecated", nullable = false)
    public boolean deprecated = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition_snapshot", columnDefinition = "jsonb")
    public Object definitionSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_data", columnDefinition = "jsonb")
    public Object extraData;

    @Column(name = "updated_at", nullable = false)
    @org.hibernate.annotations.UpdateTimestamp
    public OffsetDateTime updatedAt;

    /**
     * 获取时间
     */
    @Column(name = "acquired_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime acquiredAt;
}
