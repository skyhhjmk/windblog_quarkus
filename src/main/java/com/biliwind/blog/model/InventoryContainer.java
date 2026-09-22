package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A root warehouse or an item-owned nested grid. */
@Entity
@Table(name = "inventory_containers")
public class InventoryContainer extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "parent_item_uuid", unique = true)
    public UUID parentItemUuid;

    @Column(nullable = false)
    public Integer rows;

    @Column(nullable = false)
    public Integer columns;

    @Column(nullable = false)
    public Long revision = 0L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Object snapshot;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
