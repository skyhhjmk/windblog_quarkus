package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wesp_sync_receipts")
public class WespSyncReceipt extends PanacheEntityBase {
    @Id
    @Column(name = "receipt_id", length = 100)
    public String receiptId;

    @Column(name = "node_id", nullable = false, length = 100)
    public String nodeId;

    @Column(name = "stage", nullable = false, length = 32)
    public String stage;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    public String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
