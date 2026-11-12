package com.biliwind.blog.service.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single declaration of which row types may be replicated and how. Every
 * other participant (the outbox publisher, the edge applier and the WESP
 * receiver whitelist) asks this registry instead of keeping its own list.
 */
@ApplicationScoped
public class EdgeSyncRegistry {

    private final Map<String, EdgeSyncEntity> entities = new LinkedHashMap<>();

    @Inject
    Instance<EdgeSyncDataApplyService> applier;

    @Inject
    Instance<EdgeDataSyncService> publisher;

    @Inject
    ObjectMapper objectMapper;

    @PostConstruct
    void initialize() {
        register(EdgeSyncEntity.builder("POST")
                .propagated((entityId, action) -> publisher.get().publishPost(entityId))
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applyPost(entityId, payload)))
                .delete(entityId -> applier.get().deletePost(entityId))
                .version(this::postPayloadVersion,
                        (entityId, payload) -> applier.get().currentPostVersion(entityId))
                .build());
        register(EdgeSyncEntity.builder("TAG")
                .propagated((entityId, action) -> publisher.get().publishTag(entityId, action))
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applyTag(entityId, payload)))
                .delete(entityId -> applier.get().deleteTag(entityId))
                .build());
        register(EdgeSyncEntity.builder("CATEGORY")
                .propagated((entityId, action) -> publisher.get().publishCategory(entityId, action))
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applyCategory(entityId, payload)))
                .delete(entityId -> applier.get().deleteCategory(entityId))
                .build());
        register(EdgeSyncEntity.builder("MEDIA")
                .propagated((entityId, action) -> publisher.get().publishMedia(entityId, action))
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applyMedia(entityId, payload)))
                .delete(entityId -> applier.get().deleteMedia(entityId))
                .version(this::flatPayloadVersion,
                        (entityId, payload) -> applier.get().currentMediaVersion(entityId))
                .build());
        register(EdgeSyncEntity.builder("SYSTEM_SETTING")
                .propagated((entityId, action) -> publisher.get().publishSystemSetting(entityId, action))
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applySystemSetting(entityId, payload)))
                .version(this::flatPayloadVersion,
                        (entityId, payload) -> applier.get().currentSystemSettingVersion(payload))
                .build());
        register(EdgeSyncEntity.builder("REGION_RULE")
                .propagated((entityId, action) -> publisher.get().publishRegionRule(entityId, action))
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applyRegionRule(entityId, payload)))
                .delete(entityId -> applier.get().deleteRegionRule(entityId))
                .build());
        register(EdgeSyncEntity.builder("NODE_REGION")
                .edgeApplied()
                .apply((entityId, payload) -> applyChecked(() -> applier.get().applyNodeRegion(payload)))
                .build());
        register(EdgeSyncEntity.builder("CLUSTER_PUBLIC_KEY")
                .edgeApplied()
                .apply((entityId, payload) -> applier.get().applyClusterPublicKey(payload))
                .build());
        register(EdgeSyncEntity.builder("MEDIA_MANIFEST")
                .transport(EdgeSyncEntity.TransportKind.MEDIA_MANIFEST)
                .build());
        register(EdgeSyncEntity.builder("SYNC_REQUEST")
                .transport(EdgeSyncEntity.TransportKind.FULL_SYNC_REQUEST)
                .build());
    }

    private void register(EdgeSyncEntity entity) {
        EdgeSyncEntity previous = entities.put(entity.entityType(), entity);
        if (previous != null) {
            throw new IllegalStateException("重复的边缘同步实体声明: " + entity.entityType());
        }
    }

    public Optional<EdgeSyncEntity> find(String entityType) {
        return entityType == null ? Optional.empty() : Optional.ofNullable(entities.get(entityType));
    }

    public Set<String> entityTypes() {
        return entities.keySet();
    }

    public boolean isPropagated(String entityType) {
        return find(entityType).map(EdgeSyncEntity::propagated).orElse(false);
    }

    public boolean isEdgeApplied(String entityType) {
        return find(entityType).map(EdgeSyncEntity::edgeApplied).orElse(false);
    }

    public boolean acceptsInbound(String entityType) {
        return find(entityType)
                .map(entity -> entity.edgeApplied() || entity.transport() != EdgeSyncEntity.TransportKind.NONE)
                .orElse(false);
    }

    public EdgeSyncEntity.TransportKind transportKind(String entityType) {
        return find(entityType).map(EdgeSyncEntity::transport).orElse(EdgeSyncEntity.TransportKind.NONE);
    }

    public EdgeSyncEntity.PublishedOperation snapshot(String entityType, long entityId, String action) {
        EdgeSyncEntity entity = find(entityType)
                .filter(EdgeSyncEntity::propagated)
                .orElseThrow(() -> new IllegalArgumentException("不支持的 EDGE_SYNC 类型: " + entityType));
        return entity.snapshot().build(entityId, action);
    }

    public EdgeSyncEntity.ApplyOutcome apply(String entityType, long entityId, String payload) {
        EdgeSyncEntity entity = find(entityType).filter(EdgeSyncEntity::edgeApplied).orElse(null);
        if (entity == null) {
            return EdgeSyncEntity.ApplyOutcome.IGNORED;
        }
        long incomingVersion = entity.payloadVersion().applyAsLong(payload);
        if (incomingVersion > 0 && entity.currentVersion().versionOf(entityId, payload) > incomingVersion) {
            return EdgeSyncEntity.ApplyOutcome.SKIPPED_STALE;
        }
        return entity.applier().apply(entityId, payload);
    }

    public EdgeSyncEntity.ApplyOutcome delete(String entityType, long entityId) {
        EdgeSyncEntity entity = find(entityType).filter(EdgeSyncEntity::edgeApplied).orElse(null);
        if (entity == null) {
            return EdgeSyncEntity.ApplyOutcome.IGNORED;
        }
        if (entity.deleter() == null) {
            return EdgeSyncEntity.ApplyOutcome.APPLIED;
        }
        return entity.deleter().delete(entityId);
    }

    private long postPayloadVersion(String payload) {
        JsonNode root = readTree(payload);
        return root == null ? 0L : root.path("post").path("version").asLong(0L);
    }

    private long flatPayloadVersion(String payload) {
        JsonNode root = readTree(payload);
        return root == null ? 0L : root.path("version").asLong(0L);
    }

    private JsonNode readTree(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            return null;
        }
    }

    @FunctionalInterface
    private interface CheckedApplier {
        EdgeSyncEntity.ApplyOutcome apply() throws Exception;
    }

    private static EdgeSyncEntity.ApplyOutcome applyChecked(CheckedApplier action) {
        try {
            return action.apply();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
