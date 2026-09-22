package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** Per-user warehouse revision and cached full snapshot metadata. */
@Entity
@Table(name = "inventory_warehouses")
public class InventoryWarehouse extends PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    public Long userId;

    @Column(nullable = false)
    public Long revision = 0L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Object snapshot;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
