package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 商店物品实体，对应 store_items
 */
@Entity
@Table(name = "store_items")
public class StoreItem extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "item_code", length = 128, unique = true)
    public String itemCode;

    @Column(name = "definition_version", length = 64, nullable = false)
    public String definitionVersion = "1";

    @Column(name = "deprecated", nullable = false)
    public boolean deprecated = false;

    /**
     * 物品名称
     */
    @Column(nullable = false, length = 128)
    public String name;

    /**
     * 物品描述
     */
    @Column(columnDefinition = "text")
    public String description;

    /**
     * 价格（积分）
     */
    @Column(nullable = false)
    public Long price;

    /**
     * 稀有度颜色标识 (例如：#FFD700)
     */
    @Column(length = 32)
    public String rarity;

    /**
     * 物品类型（如：archive, url, code 等）
     */
    @Column(length = 32)
    public String type;

    /**
     * 扩展信息（JSONB: 存储网盘URL、密码、压缩包路径、兑换码等）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_info", columnDefinition = "jsonb")
    public Object extraInfo;

    /**
     * 状态：0下架，1上架
     */
    @Column(nullable = false)
    public short status = 1;

    @Column(nullable = false)
    public Integer width = 1;

    @Column(nullable = false)
    public Integer height = 1;

    @Column(name = "stackable", nullable = false)
    public boolean stackable = false;

    @Column(name = "max_stack_size", nullable = false)
    public Integer maxStackSize = 1;

    @Column(name = "container_rows")
    public Integer containerRows;

    @Column(name = "container_columns")
    public Integer containerColumns;

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
