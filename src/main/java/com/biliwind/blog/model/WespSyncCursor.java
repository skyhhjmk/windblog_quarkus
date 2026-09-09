package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wesp_sync_cursors")
public class WespSyncCursor extends PanacheEntityBase {
    @Id
    @Column(name = "peer_id", length = 100)
    public String peerId;

    @Column(name = "cursor_row", nullable = false)
    public long cursorRow;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "full_sync_requested", nullable = false)
    public boolean fullSyncRequested;
}
