package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Highest contiguous WESP sequence applied for one sender incarnation and
 * dataset. It lets a receiver refuse to skip a missing sequence.
 */
@Entity
@Table(name = "wesp_sync_sequence_frontiers")
public class WespSyncSequenceFrontier extends PanacheEntityBase {

    @EmbeddedId
    public Key id;

    @Column(name = "applied_seq", nullable = false)
    public long appliedSeq;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "node_id", nullable = false, length = 100)
        public String nodeId;

        @Column(name = "incarnation", nullable = false, length = 100)
        public String incarnation;

        @Column(name = "dataset_id", nullable = false, length = 100)
        public String datasetId;

        public Key() {
        }

        public Key(String nodeId, String incarnation, String datasetId) {
            this.nodeId = nodeId;
            this.incarnation = incarnation;
            this.datasetId = datasetId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(nodeId, key.nodeId)
                    && Objects.equals(incarnation, key.incarnation)
                    && Objects.equals(datasetId, key.datasetId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(nodeId, incarnation, datasetId);
        }
    }
}
