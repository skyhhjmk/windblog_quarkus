package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "edge_sync_records")
public class EdgeSyncRecord extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "node_id", nullable = false, length = 100)
    public String nodeId;

    @Column(name = "entity_type", nullable = false, length = 64)
    public String entityType;

    @Column(name = "entity_id", nullable = false, length = 64)
    public String entityId;

    @Column(name = "action", nullable = false, length = 32)
    public String action;

    @Column(name = "status", nullable = false, length = 32)
    public String status;

    @Column(name = "retry_count", nullable = false)
    public int retryCount;

    @Column(name = "error_message", length = 1000)
    public String errorMessage;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
