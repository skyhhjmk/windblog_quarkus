package com.biliwind.blog.service.edge;

import java.util.function.ToLongFunction;

/**
 * One declaration of how a replicated row type travels between the primary and
 * edge nodes: how it is serialised, applied, deleted and version-guarded.
 */
public final class EdgeSyncEntity {

    public enum ApplyOutcome {
        APPLIED,
        SKIPPED_STALE,
        IGNORED
    }

    public enum TransportKind {
        NONE,
        MEDIA_MANIFEST,
        FULL_SYNC_REQUEST
    }

    public record PublishedOperation(String action, String payload) {
    }

    @FunctionalInterface
    public interface SnapshotBuilder {
        PublishedOperation build(long entityId, String action);
    }

    @FunctionalInterface
    public interface PayloadApplier {
        ApplyOutcome apply(long entityId, String payload);
    }

    @FunctionalInterface
    public interface EntityDeleter {
        ApplyOutcome delete(long entityId);
    }

    @FunctionalInterface
    public interface CurrentVersionLookup {
        long versionOf(long entityId, String payload);
    }

    private final String entityType;
    private final boolean propagated;
    private final boolean edgeApplied;
    private final TransportKind transport;
    private final SnapshotBuilder snapshot;
    private final PayloadApplier applier;
    private final EntityDeleter deleter;
    private final ToLongFunction<String> payloadVersion;
    private final CurrentVersionLookup currentVersion;

    private EdgeSyncEntity(Builder builder) {
        this.entityType = builder.entityType;
        this.propagated = builder.propagated;
        this.edgeApplied = builder.edgeApplied;
        this.transport = builder.transport;
        this.snapshot = builder.snapshot;
        this.applier = builder.applier;
        this.deleter = builder.deleter;
        this.payloadVersion = builder.payloadVersion;
        this.currentVersion = builder.currentVersion;
    }

    public String entityType() {
        return entityType;
    }

    public boolean propagated() {
        return propagated;
    }

    public boolean edgeApplied() {
        return edgeApplied;
    }

    public TransportKind transport() {
        return transport;
    }

    public SnapshotBuilder snapshot() {
        return snapshot;
    }

    public PayloadApplier applier() {
        return applier;
    }

    public EntityDeleter deleter() {
        return deleter;
    }

    public ToLongFunction<String> payloadVersion() {
        return payloadVersion == null ? payload -> 0L : payloadVersion;
    }

    public CurrentVersionLookup currentVersion() {
        return currentVersion == null ? (entityId, payload) -> 0L : currentVersion;
    }

    static Builder builder(String entityType) {
        return new Builder(entityType);
    }

    static final class Builder {
        private final String entityType;
        private boolean propagated;
        private boolean edgeApplied;
        private TransportKind transport = TransportKind.NONE;
        private SnapshotBuilder snapshot;
        private PayloadApplier applier;
        private EntityDeleter deleter;
        private ToLongFunction<String> payloadVersion;
        private CurrentVersionLookup currentVersion;

        private Builder(String entityType) {
            this.entityType = entityType;
        }

        Builder propagated(SnapshotBuilder value) {
            this.propagated = true;
            this.snapshot = value;
            return this;
        }

        Builder edgeApplied() {
            this.edgeApplied = true;
            return this;
        }

        Builder transport(TransportKind kind) {
            this.transport = kind;
            return this;
        }

        Builder apply(PayloadApplier value) {
            this.applier = value;
            return this;
        }

        Builder delete(EntityDeleter value) {
            this.deleter = value;
            return this;
        }

        Builder version(ToLongFunction<String> payloadVersion, CurrentVersionLookup currentVersion) {
            this.payloadVersion = payloadVersion;
            this.currentVersion = currentVersion;
            return this;
        }

        EdgeSyncEntity build() {
            if (propagated && snapshot == null) {
                throw new IllegalStateException("propagated entity requires a snapshot: " + entityType);
            }
            if (edgeApplied && applier == null) {
                throw new IllegalStateException("edge-applied entity requires an applier: " + entityType);
            }
            if (!propagated && !edgeApplied && transport == TransportKind.NONE) {
                throw new IllegalStateException("entity does not participate in replication: " + entityType);
            }
            return new EdgeSyncEntity(this);
        }
    }
}
