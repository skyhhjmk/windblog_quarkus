package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Idempotency ledger for inventory mutations. */
@Entity
@Table(name = "inventory_operations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_inventory_operation_key", columnNames = {"user_id", "idempotency_key"})
})
public class InventoryOperation extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    public String idempotencyKey;

    @Column(name = "operation_type", nullable = false, length = 40)
    public String operationType;

    @Column(name = "operation_id", nullable = false, unique = true)
    public UUID operationId;

    @Column(name = "request_revision")
    public Long requestRevision;

    @Column(name = "result_instance_uuid")
    public UUID resultInstanceUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    public Object result;

    @Column(name = "trace_id", length = 128)
    public String traceId;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
