package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Durable WESP v1 operation inbox/outbox row. */
@Entity
@Table(name = "wesp_sync_operations")
public class WespSyncOperation extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    public Long rowId;

    @Column(name = "op_id", nullable = false, unique = true, length = 200)
    public String opId;

    @Column(name = "node_id", nullable = false, length = 100)
    public String nodeId;

    @Column(name = "dataset_id", nullable = false, length = 100)
    public String datasetId;

    @Column(name = "incarnation", nullable = false, length = 100)
    public String incarnation;

    @Column(name = "seq", nullable = false)
    public long seq;

    @Column(name = "entity_type", nullable = false, length = 64)
    public String entityType;

    @Column(name = "action", nullable = false, length = 32)
    public String action;

    @Column(name = "entity_id", nullable = false, length = 128)
    public String entityId;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    public String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    public String payloadHash;

    @Column(name = "status", nullable = false, length = 32)
    public String status = "PENDING";

    @Column(name = "sent_at")
    public OffsetDateTime sentAt;

    @Column(name = "applied_at")
    public OffsetDateTime appliedAt;

    @Column(name = "last_error", length = 512)
    public String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
