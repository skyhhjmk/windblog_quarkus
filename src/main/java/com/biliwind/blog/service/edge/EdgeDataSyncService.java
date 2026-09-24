package com.biliwind.blog.service.edge;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.edge.EdgeServiceProto;
import com.biliwind.blog.edge.MutinyEdgeNodeServiceGrpc;
import com.biliwind.blog.model.*;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.panache.common.Page;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApplicationScoped
public class EdgeDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(EdgeDataSyncService.class);
    private static final int PUBLIC_FULL_SYNC_BATCH_SIZE = 200;
    private final Map<String, SyncProgress> syncProgressMap = new ConcurrentHashMap<>();
    @Inject
    EdgeNodeRegistry registry;
    @Inject
    ObjectMapper objectMapper;
    @Inject
    EntityManager entityManager;
    @Inject
    com.biliwind.blog.common.helper.RsaHelper rsaHelper;
    @Inject
    GrpcChannelFactory channelFactory;
    @Inject
    PrimaryEdgeChannelRegistry primaryEdgeChannelRegistry;
    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    com.biliwind.blog.service.PostAccessService postAccessService;
    @Inject
    StorageService storageService;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    jakarta.enterprise.inject.Instance<com.biliwind.blog.service.OutboxEventService> outboxEventService;

    @Inject
    WespSyncService wespSyncService;

    void onStart(@Observes StartupEvent ev) {
        if (nodeRoleService.isEdgeNode()) {
            log.info("当前节点是边缘节点，跳过主节点数据广播初始化");
            return;
        }

        log.info("EdgeDataSyncService started");
        syncClusterKeyToAll();
    }

    /**
     * 将主节点的公钥同步到所有边缘节点
     */
    public void syncClusterKeyToAll() {
        String publicKey = rsaHelper.getPublicKeyEncoded();
        if (publicKey == null) {
            log.warn("Main node public key is not initialized, skipping cluster key sync");
            return;
        }
        log.info("Broadcasting cluster public key to all nodes");
        broadcastSync("CLUSTER_PUBLIC_KEY", "UPDATE", "MAIN", publicKey);
    }


    /**
     * 监听文章同步事件
     */
    public void onPostSynced(@Observes(during = TransactionPhase.AFTER_SUCCESS) PostSyncedEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }

        log.info("Detected post change, enqueueing edge sync: {}", event.getPostId());
        enqueueEdgeSync("POST", event.getPostId(), "UPSERT");
    }

    /**
     * 监听通用同步事件（如标签）
     */
    public void onDataChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) DataSyncEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }

        log.info("Detected {} change, enqueueing edge sync: {}, id: {}",
                event.getEntityType(), event.getAction(), event.getEntityId());
        if (isOutboxSyncType(event.getEntityType())) {
            enqueueEdgeSync(event.getEntityType(), event.getEntityId(), event.getAction());
        }
    }

    private boolean isOutboxSyncType(String entityType) {
        return "POST".equals(entityType) || "TAG".equals(entityType)
                || "CATEGORY".equals(entityType) || "MEDIA".equals(entityType);
    }

    private void enqueueEdgeSync(String entityType, Long entityId, String action) {
        if (entityId == null || action == null || !isOutboxSyncType(entityType)) {
            return;
        }
        if (wespSyncService.isEnabled()) {
            // WESP owns the durable operation log. Persist the public change
            // directly so the primary does not depend on the lightweight edge
            // profile's disabled RabbitMQ/outbox dispatcher.
            self.get().dispatchOutboxSync(entityType, entityId, action);
            return;
        }
        outboxEventService.get().enqueue(
                "EDGE_SYNC:" + entityType + ":" + entityId + ":" + action + ":" + java.util.UUID.randomUUID(),
                "EDGE_SYNC",
                entityType,
                entityId.toString(),
                Map.of("entityType", entityType, "entityId", entityId, "action", action),
                null);
    }

    @Transactional
    @jakarta.enterprise.context.control.ActivateRequestContext
    public void dispatchOutboxSync(String entityType, Long entityId, String action) {
        if (entityId == null || action == null) {
            throw new IllegalArgumentException("EDGE_SYNC payload 不完整");
        }
        if ("POST".equals(entityType)) {
            syncPost(entityId);
        } else if ("TAG".equals(entityType)) {
            syncTag(entityId, action);
        } else if ("CATEGORY".equals(entityType)) {
            syncCategory(entityId, action);
        } else if ("MEDIA".equals(entityType)) {
            syncMedia(entityId, action);
        } else {
            throw new IllegalArgumentException("不支持的 EDGE_SYNC 类型: " + entityType);
        }
    }

    private void syncCategory(Long categoryId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Category category = Category.findById(categoryId);
            if (category == null) return;
            try {
                payload = objectMapper.writeValueAsString(buildPublicCategoryNode(category));
            } catch (Exception e) {
                log.error("Failed to serialize category: {}", SensitiveMessageSanitizer.sanitize(e.getMessage()));
                return;
            }
        }
        broadcastSync("CATEGORY", action, categoryId.toString(), payload);
    }

    private void syncMedia(Long mediaId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Media media = Media.findById(mediaId);
            if (media == null) return;
            if (postAccessService.hasProtectedMediaReference(mediaId)) {
                broadcastSync("MEDIA", "DELETE", mediaId.toString(), "{}");
                return;
            }
            try {
                payload = objectMapper.writeValueAsString(buildPublicMediaNode(media));
            } catch (Exception e) {
                log.error("Failed to serialize media: {}", SensitiveMessageSanitizer.sanitize(e.getMessage()));
                return;
            }
        }
        broadcastSync("MEDIA", action, mediaId.toString(), payload);
    }

    private ObjectNode buildPublicMediaNode(Media media) {
        ObjectNode publicMedia = objectMapper.createObjectNode();
        putLong(publicMedia, "id", media.id);
        putText(publicMedia, "storageKey", media.storageKey);
        putText(publicMedia, "url", media.storageClasses == null ? media.url
                : storageService.getBestAccessUrl(media, VariantType.ORIGINAL));
        putShort(publicMedia, "mediaType", media.mediaType);
        putText(publicMedia, "mimeType", media.mimeType);
        putText(publicMedia, "fileName", media.fileName);
        putLong(publicMedia, "size", media.size);
        putLong(publicMedia, "uploadedBy", media.uploadedBy);
        putInteger(publicMedia, "width", media.width);
        putInteger(publicMedia, "height", media.height);
        publicMedia.set("alt", objectMapper.valueToTree(media.alt));
        ObjectNode safeMetadata = safePublicMediaMetadata(media.metadata);
        if (media.storageClasses != null) {
            putCompliantVariantUrl(safeMetadata, "webpUrl", media, VariantType.WEBP);
            putCompliantVariantUrl(safeMetadata, "thumbnailUrl", media, VariantType.WEBP);
            putCompliantVariantUrl(safeMetadata, "placeholderUrl", media, VariantType.PLACEHOLDER);
            putCompliantVariantUrl(safeMetadata, "previewUrl", media, VariantType.PLACEHOLDER);
            putCompliantVariantUrl(safeMetadata, "coverUrl", media, VariantType.COVER);
        }
        publicMedia.set("metadata", safeMetadata);
        publicMedia.set("storageClasses", safePublicStorageClasses(media));
        publicMedia.set("visibilityRegions", objectMapper.valueToTree(media.visibilityRegions));
        publicMedia.set("hiddenRegions", objectMapper.valueToTree(media.hiddenRegions));
        publicMedia.set("syncStorageClasses", objectMapper.valueToTree(media.syncStorageClasses));
        publicMedia.set("skipStorageClasses", objectMapper.valueToTree(media.skipStorageClasses));
        putInteger(publicMedia, "version", media.version);
        putDateTime(publicMedia, "createdAt", media.createdAt);
        putDateTime(publicMedia, "updatedAt", media.updatedAt);
        putText(publicMedia, "processingStatus", media.processingStatus);
        putInteger(publicMedia, "processingProgress", media.processingProgress);
        putText(publicMedia, "virusScanStatus", media.virusScanStatus);
        putDateTime(publicMedia, "virusScannedAt", media.virusScannedAt);
        putText(publicMedia, "virusScanMessage", media.virusScanMessage);
        return publicMedia;
    }

    private ObjectNode safePublicMediaMetadata(Map<String, Object> metadata) {
        ObjectNode result = objectMapper.createObjectNode();
        if (metadata == null) {
            return result;
        }
        for (String key : List.of("placeholderUrl", "thumbnailUrl", "previewUrl", "webpUrl",
                "coverUrl", "width", "height")) {
            JsonNode value = objectMapper.valueToTree(metadata.get(key));
            if (value != null && !value.isNull()) {
                result.set(key, value);
            }
        }
        return result;
    }

    private void putCompliantVariantUrl(ObjectNode metadata, String key, Media media, VariantType variant) {
        String url = storageService.getBestAccessUrl(media, variant);
        if (url == null || url.isBlank()) {
            metadata.remove(key);
        } else {
            metadata.put(key, url);
        }
    }

    private ObjectNode safePublicStorageClasses(Media media) {
        ObjectNode result = objectMapper.createObjectNode();
        Map<String, Object> storageClasses = media.storageClasses;
        if (storageClasses == null) {
            return result;
        }
        for (Map.Entry<String, Object> providerEntry : storageClasses.entrySet()) {
            if (!storageService.isNormalAccessAllowed(media, providerEntry.getKey())) {
                continue;
            }
            if (!(providerEntry.getValue() instanceof Map<?, ?> providerData)) {
                continue;
            }
            ObjectNode variants = objectMapper.createObjectNode();
            for (Map.Entry<?, ?> variantEntry : providerData.entrySet()) {
                if (!(variantEntry.getKey() instanceof String variantName)
                        || !(variantEntry.getValue() instanceof Map<?, ?> variantData)
                        || !"synced".equals(variantData.get("status"))) {
                    continue;
                }
                ObjectNode safeVariant = objectMapper.createObjectNode();
                copyScalar(variantData, safeVariant, "status");
                copyScalar(variantData, safeVariant, "path");
                copyScalar(variantData, safeVariant, "size");
                variants.set(variantName, safeVariant);
            }
            result.set(providerEntry.getKey(), variants);
        }
        return result;
    }

    private void copyScalar(Map<?, ?> source, ObjectNode target, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.set(key, objectMapper.valueToTree(value));
        }
    }

    private void syncTag(Long tagId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Tag tag = Tag.findById(tagId);
            if (tag == null) return;
            try {
                payload = objectMapper.writeValueAsString(buildPublicTagNode(tag));
            } catch (Exception e) {
                log.error("Failed to serialize tag: {}", SensitiveMessageSanitizer.sanitize(e.getMessage()));
                return;
            }
        }

        broadcastSync("TAG", action, tagId.toString(), payload);
    }

    private void syncPost(Long postId) {
        Post post = Post.findById(postId);
        String action = "UPSERT";
        String payload = "{}";

        if (post == null || !canSyncPostToEdge(post)) {
            action = "DELETE";
        } else {
            try {
                Map<String, Object> bundle = buildPublicPostBundle(post);

                // 包含标签关联
                List<PostTag> postTags = PostTag.find("post.id = ?1", postId).list();
                java.util.ArrayList<ObjectNode> tags = new java.util.ArrayList<>();
                for (PostTag postTag : postTags) {
                    tags.add(buildPublicTagNode(postTag.tag));
                }
                bundle.put("tags", tags);

                payload = objectMapper.writeValueAsString(bundle);
            } catch (Exception e) {
                log.error("Failed to serialize post bundle: {}", SensitiveMessageSanitizer.sanitize(e.getMessage()));
                return;
            }
        }

        broadcastSync("POST", action, postId.toString(), payload);
    }

    private Map<String, Object> buildPublicPostBundle(Post post) {
        Map<String, Object> bundle = new HashMap<>();
        ObjectNode publicPost = buildPublicPostNode(post);
        ObjectNode publicRevision = buildPublicRevision(post);
        publicPost.set("publishedRevision", publicRevision);
        bundle.put("post", publicPost);
        if (publicRevision != null) {
            // Apply service accepts this compatibility key and merges only the public revision.
            bundle.put("currentRevision", publicRevision);
        }
        return bundle;
    }

    private ObjectNode buildPublicPostNode(Post post) {
        ObjectNode publicPost = objectMapper.createObjectNode();
        putLong(publicPost, "id", post.id);
        putText(publicPost, "slug", post.slug);
        publicPost.set("title", objectMapper.valueToTree(post.title));
        publicPost.set("summary", objectMapper.valueToTree(post.summary));
        publicPost.set("aiSummary", objectMapper.valueToTree(post.aiSummary));
        putShort(publicPost, "aiSummaryStatus", post.aiSummaryStatus);
        putText(publicPost, "status", post.status == null ? null : post.status.name());
        putShort(publicPost, "visibility", post.visibility);
        putText(publicPost, "seoTitle", post.seoTitle);
        putText(publicPost, "seoKeywords", post.seoKeywords);
        putText(publicPost, "seoDescription", post.seoDescription);
        putText(publicPost, "renderType", post.renderType == null ? null : post.renderType.name());
        putReference(publicPost, "user", post.user == null ? null : post.user.id);
        // WESP never transfers account credentials, but the public read model
        // still needs the author's visible name on the edge node.
        putText(publicPost, "authorName", PostAuthorHelper.displayName(post));
        putReference(publicPost, "category", post.category == null ? null : post.category.id);
        publicPost.set("extraInfo", safePublicExtraInfo(post.extraInfo));
        publicPost.set("visibilityRegions", objectMapper.valueToTree(post.visibilityRegions));
        publicPost.set("contentDeclarations", objectMapper.valueToTree(post.contentDeclarations));
        putText(publicPost, "repostPolicyCode", repostPolicyCatalog.resolve(post).code());
        putDateTime(publicPost, "publishedAt", post.publishedAt);
        putDateTime(publicPost, "createdAt", post.createdAt);
        putDateTime(publicPost, "updatedAt", post.updatedAt);
        putLong(publicPost, "viewCount", post.viewCount);
        putBoolean(publicPost, "featured", post.featured);
        putBoolean(publicPost, "allowComment", post.allowComment);
        putInteger(publicPost, "version", post.version);
        return publicPost;
    }

    private ObjectNode buildPublicRevision(Post post) {
        if (post == null || post.publishedRevision == null) {
            return null;
        }
        PostRevision source = post.publishedRevision;
        ObjectNode revision = objectMapper.createObjectNode();
        putLong(revision, "id", source.id);
        revision.set("title", objectMapper.valueToTree(source.title));
        putShort(revision, "editorType", source.editorType);
        putInteger(revision, "revisionNumber", source.revisionNumber);
        putReference(revision, "createdBy", source.createdBy == null ? null : source.createdBy.id);
        putDateTime(revision, "createdAt", source.createdAt);
        if (source.contentMarkdown == null) {
            revision.set("contentMarkdown", objectMapper.createObjectNode());
            return revision;
        }
        Map<String, String> publicContent = new HashMap<>();
        long postPrice = postAccessService.getPostPrice(post);
        int freeLines = postAccessService.getFreeLines(post);
        for (Map.Entry<String, String> entry : source.contentMarkdown.entrySet()) {
            publicContent.put(entry.getKey(), postAccessService.getPreviewOnlyContent(
                    entry.getValue(), freeLines, postPrice > 0, post.id, postPrice, null));
        }
        revision.set("contentMarkdown", objectMapper.valueToTree(publicContent));
        return revision;
    }

    private JsonNode safePublicExtraInfo(Object extraInfo) {
        ObjectNode result = objectMapper.createObjectNode();
        if (extraInfo == null) {
            return result;
        }
        JsonNode source = objectMapper.valueToTree(extraInfo);
        if (!source.isObject()) {
            return result;
        }
        copyPublicExtraField(source, result, "points_price");
        copyPublicExtraField(source, result, "free_lines");
        copyPublicExtraField(source, result, "related_store_items");
        return result;
    }

    private void copyPublicExtraField(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && !value.isNull()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private void putReference(ObjectNode target, String fieldName, Long id) {
        if (id == null) {
            target.putNull(fieldName);
            return;
        }
        ObjectNode reference = objectMapper.createObjectNode();
        reference.put("id", id);
        target.set(fieldName, reference);
    }

    private void putText(ObjectNode target, String fieldName, String value) {
        if (value == null) {
            target.putNull(fieldName);
            return;
        }
        target.put(fieldName, value);
    }

    private void putLong(ObjectNode target, String fieldName, Long value) {
        if (value == null) {
            target.putNull(fieldName);
            return;
        }
        target.put(fieldName, value);
    }

    private void putInteger(ObjectNode target, String fieldName, Integer value) {
        if (value == null) {
            target.putNull(fieldName);
            return;
        }
        target.put(fieldName, value);
    }

    private void putShort(ObjectNode target, String fieldName, Short value) {
        if (value == null) {
            target.putNull(fieldName);
            return;
        }
        target.put(fieldName, value.shortValue());
    }

    private void putShort(ObjectNode target, String fieldName, short value) {
        target.put(fieldName, value);
    }

    private void putBoolean(ObjectNode target, String fieldName, Boolean value) {
        if (value == null) {
            target.putNull(fieldName);
            return;
        }
        target.put(fieldName, value.booleanValue());
    }

    private void putDateTime(ObjectNode target, String fieldName, java.time.OffsetDateTime value) {
        if (value == null) {
            target.putNull(fieldName);
            return;
        }
        target.put(fieldName, value.toString());
    }

    private void broadcastSync(String entityType, String action, String entityId, String payload) {
        if (wespSyncService.isEnabled()) {
            // WESP persists the operation first and delivers it from the node that
            // initiated the request. This prevents the old primary-to-edge gRPC
            // push from opening an inbound path to a home node.
            wespSyncService.enqueueLocalOperation(entityType, action, entityId, payload);
            return;
        }
        List<EdgeNode> allNodes = registry.getAllNodes();
        java.util.ArrayList<EdgeNode> nodes = new java.util.ArrayList<>();
        for (EdgeNode node : allNodes) {
            if (node.isEnabled != null && node.isEnabled) {
                nodes.add(node);
            }
        }

        if (nodes.isEmpty()) {
            log.debug("No active edge nodes to sync {} {}", entityType, entityId);
            return;
        }

        for (EdgeNode node : nodes) {
            pushToNode(node, entityType, action, entityId, payload);
        }
    }

    private final Map<String, MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub> stubCache = new ConcurrentHashMap<>();
    @Inject
    jakarta.enterprise.inject.Instance<EdgeDataSyncService> self;

    private MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub getStub(EdgeNode node) {
        String grpcAddress = node.grpcAddress;
        if (grpcAddress == null || grpcAddress.isEmpty()) {
            grpcAddress = node.address;
        }
        return stubCache.computeIfAbsent(grpcAddress, addr ->
                MutinyEdgeNodeServiceGrpc.newMutinyStub(channelFactory.createChannel(addr, node.nodeId))
        );
    }

    private void removeCachedStub(EdgeNode node) {
        String grpcAddress = resolveGrpcAddress(node);
        if (grpcAddress == null || grpcAddress.isEmpty()) {
            return;
        }
        stubCache.remove(grpcAddress);
    }

    private void pushToNode(EdgeNode node, String entityType, String action, String entityId, String payload) {
        Long syncRecordId = createSyncRecord(node, entityType, action, entityId, 0);
        pushToNodeAsync(node, entityType, action, entityId, payload).subscribe().with(
                new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean success) {
                        if (success) {
                            log.debug("Successfully pushed {} {} to node {}", entityType, entityId, node.nodeId);
                            updateSyncRecord(syncRecordId, "SUCCESS", null);
                        } else {
                            updateSyncRecord(syncRecordId, "FAILED", "节点未确认同步请求");
                        }
                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable error) {
                        log.error("Failed to push {} {} to node {}", entityType, entityId, node.nodeId, error);
                        updateSyncRecord(syncRecordId, "FAILED",
                                SensitiveMessageSanitizer.sanitize(error.getMessage()));
                    }
                }
        );
    }

    private Uni<Boolean> pushToNodeAsync(EdgeNode node, String entityType, String action, String entityId, String payload) {
        String grpcAddress = resolveGrpcAddress(node);
        EdgeServiceProto.SyncDataRequest request = EdgeServiceProto.SyncDataRequest.newBuilder()
                .setEntityType(entityType)
                .setAction(action)
                .setPayload(payload)
                .setEntityId(parseEntityId(entityId))
                .build();

        boolean sentByPersistentChannel = sendByPersistentChannel(node, request);
        if (sentByPersistentChannel) {
            return Uni.createFrom().item(true);
        }

        if (grpcAddress == null || grpcAddress.isEmpty()) {
            return Uni.createFrom().item(false);
        }

        try {
            MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = getStub(node);

            return stub.syncData(request)
                    .map(response -> {
                        if (response.getSuccess()) {
                            return true;
                        } else {
                            log.error("Node {} rejected {} {}: {}", node.nodeId, entityType, entityId,
                                    SensitiveMessageSanitizer.sanitize(response.getMessage()));
                            return false;
                        }
                    })
                    .onFailure().recoverWithItem(error -> {
                        log.error("Error pushing to node {}", node.nodeId, error);
                        removeCachedStub(node);
                        return false;
                    });
        } catch (Exception e) {
            log.error("Failed to initiate sync to node {}", node.nodeId, e);
            removeCachedStub(node);
            return Uni.createFrom().item(false);
        }
    }

    private boolean sendByPersistentChannel(EdgeNode node, EdgeServiceProto.SyncDataRequest request) {
        if (node == null || node.nodeId == null || node.nodeId.isBlank()) {
            return false;
        }

        EdgeServiceProto.EdgeChannelMessage message = EdgeServiceProto.EdgeChannelMessage.newBuilder()
                .setRequestId(java.util.UUID.randomUUID().toString())
                .setNodeId("main")
                .setTimestamp(System.currentTimeMillis())
                .setSyncData(request)
                .build();
        return primaryEdgeChannelRegistry.sendToNode(node.nodeId, message);
    }

    /**
     * 触发指定节点的全量同步
     */
    public void triggerFullSync(String nodeId, boolean force) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            log.warn("Cannot trigger sync for node {}: not found", nodeId);
            return;
        }

        if (resolveGrpcAddress(node) == null || resolveGrpcAddress(node).isEmpty()) {
            log.warn("Cannot trigger sync for node {}: no gRPC address configured", nodeId);
            syncProgressMap.put(nodeId, new SyncProgress(0, 0, "FAILED", "gRPC address is missing"));
            return;
        }

        log.info("Triggering full sync for node {} ({})", nodeId, resolveGrpcAddress(node));

        // 立即设置进度状态，让前端有反馈
        syncProgressMap.put(nodeId, new SyncProgress(0, 0, "SYNCING", "Initializing..."));

        // 异步执行同步任务
        Uni.createFrom().item(nodeId)
                .emitOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .subscribe().with(id -> {
                    try {
                        self.get().performPublicFullSyncInternal(id, force);
                    } catch (Exception e) {
                        log.error("Unhandled error in full sync thread for node {}: {}", id,
                                SensitiveMessageSanitizer.sanitize(e.getMessage()), e);
                        syncProgressMap.put(id, new SyncProgress(0, 0, "FAILED",
                                "Internal error: " + SensitiveMessageSanitizer.sanitize(e.getMessage())));
                    }
                });
    }

    /**
     * Sends the public snapshot in bounded keyset pages. Each page is built in a
     * short database transaction and all gRPC work happens after that transaction
     * has ended.
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    public void performPublicFullSyncInternal(String nodeId, boolean force) {
        EdgeNode node = self.get().loadNodeForFullSync(nodeId);
        if (node == null) {
            return;
        }
        try {
            PublicFullSyncCounts counts = self.get().loadPublicFullSyncCounts();
            int total = counts.total();
            int processed = 0;
            int failed = 0;
            syncProgressMap.put(nodeId, new SyncProgress(total, 0, "SYNCING", "Sending public snapshot..."));

            for (String entityType : List.of("TAG", "CATEGORY", "MEDIA", "MEDIA_REVOKED",
                    "POST", "POST_REVOKED")) {
                long lastId = 0L;
                while (true) {
                    List<FullSyncItem> batch = self.get().loadPublicFullSyncBatch(
                            force, entityType, lastId, PUBLIC_FULL_SYNC_BATCH_SIZE);
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (FullSyncItem item : batch) {
                        boolean success = pushToNodeWithRetry(
                                node, item.entityType(), item.action(), item.entityId(),
                                item.payload(), item.timeoutSeconds());
                        if (!success) {
                            failed++;
                        }
                        processed++;
                        syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING",
                                "Sending public snapshot (" + processed + "/" + total + ")"));
                        lastId = Long.parseLong(item.entityId());
                    }
                    if (batch.size() < PUBLIC_FULL_SYNC_BATCH_SIZE) {
                        break;
                    }
                }
            }
            if (failed == 0) {
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "COMPLETED", null));
            } else {
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "COMPLETED_WITH_ERRORS",
                        "公开快照同步失败 " + failed + " 项"));
            }
        } catch (Exception exception) {
            log.error("Public full sync execution failed for node {}", nodeId, exception);
            SyncProgress current = syncProgressMap.get(nodeId);
            syncProgressMap.put(nodeId, new SyncProgress(
                    current == null ? 0 : current.total(),
                    current == null ? 0 : current.processed(),
                    "FAILED", SensitiveMessageSanitizer.sanitize(exception.getMessage())));
        }
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    public PublicFullSyncCounts loadPublicFullSyncCounts() {
        long publicMediaCount = entityManager.createQuery(
                        "select count(media.id) from Media media where media.deletedAt is null",
                        Long.class)
                .getSingleResult();
        long revokedMediaCount = entityManager.createQuery(
                        "select count(distinct media.id) from PostMedia relation join relation.media media "
                                + "where media.deletedAt is not null", Long.class)
                .getSingleResult();
        long revokedPostCount = entityManager.createQuery(
                        "select count(post.id) from Post post where post.deletedAt is not null "
                                + "or post.status <> :published or post.visibility <> 0 "
                                + "or post.publishedRevision is null", Long.class)
                .setParameter("published", PostStatus.PUBLISHED)
                .getSingleResult();
        return new PublicFullSyncCounts(
                Tag.count(),
                Category.count(),
                publicMediaCount,
                revokedMediaCount,
                Post.count("status = ?1 and deletedAt is null and visibility = 0 "
                        + "and publishedRevision is not null", PostStatus.PUBLISHED),
                revokedPostCount);
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    public List<FullSyncItem> loadPublicFullSyncBatch(boolean force, String entityType,
                                                       long afterId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, PUBLIC_FULL_SYNC_BATCH_SIZE));
        String upsertAction = force ? "FORCE_UPSERT" : "UPSERT";
        List<FullSyncItem> items = new java.util.ArrayList<>();
        if ("TAG".equals(entityType)) {
            List<Tag> tags = Tag.<Tag>find("id > ?1 order by id", afterId)
                    .page(Page.ofSize(safeLimit)).list();
            for (Tag tag : tags) {
                items.add(new FullSyncItem("TAG", upsertAction, tag.id.toString(),
                        writePayload(buildPublicTagNode(tag)), 15));
            }
            return items;
        }
        if ("CATEGORY".equals(entityType)) {
            List<Category> categories = Category.<Category>find("id > ?1 order by id", afterId)
                    .page(Page.ofSize(safeLimit)).list();
            for (Category category : categories) {
                items.add(new FullSyncItem("CATEGORY", upsertAction, category.id.toString(),
                        writePayload(buildPublicCategoryNode(category)), 15));
            }
            return items;
        }
        if ("MEDIA".equals(entityType)) {
            List<Media> mediaBatch = entityManager.createQuery(
                            "select media from Media media where media.deletedAt is null "
                                    + "and media.id > :afterId order by media.id",
                            Media.class)
                    .setParameter("afterId", afterId)
                    .setMaxResults(safeLimit)
                    .getResultList();
            for (Media media : mediaBatch) {
                if (postAccessService.hasProtectedMediaReference(media.id)) {
                    items.add(new FullSyncItem("MEDIA", "DELETE", media.id.toString(), "{}", 15));
                } else {
                    items.add(new FullSyncItem("MEDIA", upsertAction, media.id.toString(),
                            writePayload(buildPublicMediaNode(media)), 15));
                }
            }
            return items;
        }
        if ("MEDIA_REVOKED".equals(entityType)) {
                List<Long> mediaIds = entityManager.createQuery(
                        "select distinct media.id from PostMedia relation join relation.media media "
                                + "where media.deletedAt is not null "
                                + "and media.id > :afterId order by media.id",
                            Long.class)
                    .setParameter("afterId", afterId)
                    .setMaxResults(safeLimit)
                    .getResultList();
            for (Long mediaId : mediaIds) {
                items.add(new FullSyncItem("MEDIA", "DELETE", mediaId.toString(), "{}", 15));
            }
            return items;
        }
        if ("POST".equals(entityType)) {
            List<Post> posts = Post.<Post>find(
                            "status = ?1 and deletedAt is null and visibility = 0 "
                                    + "and publishedRevision is not null and id > ?2 order by id",
                            PostStatus.PUBLISHED, afterId)
                    .page(Page.ofSize(safeLimit)).list();
            Map<Long, List<ObjectNode>> tagsByPost = loadTagsByPost(posts);
            for (Post post : posts) {
                Map<String, Object> bundle = buildPublicPostBundle(post);
                bundle.put("tags", tagsByPost.getOrDefault(post.id, List.of()));
                items.add(new FullSyncItem("POST", upsertAction, post.id.toString(),
                        writePayload(bundle), 30));
            }
            return items;
        }
        if ("POST_REVOKED".equals(entityType)) {
            List<Long> postIds = entityManager.createQuery(
                            "select post.id from Post post where post.id > :afterId and "
                                    + "(post.deletedAt is not null or post.status <> :published "
                                    + "or post.visibility <> 0 or post.publishedRevision is null) order by post.id",
                            Long.class)
                    .setParameter("afterId", afterId)
                    .setParameter("published", PostStatus.PUBLISHED)
                    .setMaxResults(safeLimit)
                    .getResultList();
            for (Long postId : postIds) {
                items.add(new FullSyncItem("POST", "DELETE", postId.toString(), "{}", 30));
            }
        }
        return items;
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    public EdgeNode loadNodeForFullSync(String nodeId) {
        return EdgeNode.findByNodeId(nodeId);
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    public List<FullSyncItem> buildPublicFullSyncSnapshot(boolean force) {
        List<FullSyncItem> items = new java.util.ArrayList<>();
        for (String entityType : List.of("TAG", "CATEGORY", "MEDIA", "MEDIA_REVOKED",
                "POST", "POST_REVOKED")) {
            long lastId = 0L;
            while (true) {
                List<FullSyncItem> batch = loadPublicFullSyncBatch(
                        force, entityType, lastId, PUBLIC_FULL_SYNC_BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                items.addAll(batch);
                lastId = Long.parseLong(batch.get(batch.size() - 1).entityId());
                if (batch.size() < PUBLIC_FULL_SYNC_BATCH_SIZE) {
                    break;
                }
            }
        }
        return items;
    }

    private Map<Long, List<ObjectNode>> loadTagsByPost(List<Post> posts) {
        Map<Long, List<ObjectNode>> tagsByPost = new HashMap<>();
        if (posts.isEmpty()) {
            return tagsByPost;
        }
        List<PostTag> relations = entityManager.createQuery(
                        "select relation from PostTag relation join fetch relation.tag "
                                + "where relation.post in :posts", PostTag.class)
                .setParameter("posts", posts)
                .getResultList();
        for (PostTag relation : relations) {
            tagsByPost.computeIfAbsent(relation.post.id, ignored -> new java.util.ArrayList<>())
                    .add(buildPublicTagNode(relation.tag));
        }
        return tagsByPost;
    }

    private ObjectNode buildPublicTagNode(Tag tag) {
        ObjectNode publicTag = objectMapper.createObjectNode();
        putLong(publicTag, "id", tag.id);
        putText(publicTag, "slug", tag.slug);
        publicTag.set("name", objectMapper.valueToTree(tag.name));
        publicTag.set("description", objectMapper.valueToTree(tag.description));
        putDateTime(publicTag, "createdAt", tag.createdAt);
        putDateTime(publicTag, "updatedAt", tag.updatedAt);
        return publicTag;
    }

    private ObjectNode buildPublicCategoryNode(Category category) {
        ObjectNode publicCategory = objectMapper.createObjectNode();
        putLong(publicCategory, "id", category.id);
        putReference(publicCategory, "parent", category.parent == null ? null : category.parent.id);
        putText(publicCategory, "slug", category.slug);
        publicCategory.set("name", objectMapper.valueToTree(category.name));
        publicCategory.set("description", objectMapper.valueToTree(category.description));
        putText(publicCategory, "path", category.path);
        putDateTime(publicCategory, "createdAt", category.createdAt);
        putDateTime(publicCategory, "updatedAt", category.updatedAt);
        putLong(publicCategory, "postCount", category.postCount);
        return publicCategory;
    }

    private String writePayload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成边缘公开快照", exception);
        }
    }

    public record FullSyncItem(String entityType, String action, String entityId,
                               String payload, int timeoutSeconds) {
    }

    public record PublicFullSyncCounts(long tags, long categories, long publicMedia,
                                      long revokedMedia, long publicPosts, long revokedPosts) {
        public int total() {
            long total = tags + categories + publicMedia + revokedMedia + publicPosts + revokedPosts;
            return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
    }

    /**
     * @deprecated Use the bounded public full-sync path triggered by triggerFullSync.
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    @Deprecated
    public void performFullSyncInternal(String nodeId, boolean force) {
        performPublicFullSyncInternal(nodeId, force);
    }
    /**
     * 获取节点的 gRPC 地址，优先使用 grpcAddress，回退到已废弃的 address 字段
     */
    private String resolveGrpcAddress(EdgeNode node) {
        if (node.grpcAddress != null && !node.grpcAddress.isEmpty()) {
            return node.grpcAddress;
        }
        return node.address;
    }

    private boolean pushToNodeWithRetry(EdgeNode node, String entityType, String action, String entityId, String payload, int timeoutSeconds) {
        Long syncRecordId = createSyncRecord(node, entityType, action, entityId, 0);
        EdgeServiceProto.SyncDataRequest persistentRequest = EdgeServiceProto.SyncDataRequest.newBuilder()
                .setEntityType(entityType)
                .setAction(action)
                .setPayload(payload)
                .setEntityId(parseEntityId(entityId))
                .build();
        boolean sentByPersistentChannel = sendByPersistentChannel(node, persistentRequest);
        if (sentByPersistentChannel) {
            updateSyncRecord(syncRecordId, "SUCCESS", null);
            return true;
        }

        String grpcAddress = resolveGrpcAddress(node);
        if (grpcAddress == null || grpcAddress.isEmpty()) {
            updateSyncRecord(syncRecordId, "FAILED", "节点 gRPC 地址为空");
            return false;
        }

        int maxAttempts = 3;
        int attempt = 0;
        long baseDelayMs = 1000L;

        while (attempt < maxAttempts) {
            try {
                MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = getStub(node);

                EdgeServiceProto.SyncDataRequest request = EdgeServiceProto.SyncDataRequest.newBuilder()
                        .setEntityType(entityType)
                        .setAction(action)
                        .setPayload(payload)
                        .setEntityId(parseEntityId(entityId))
                        .build();

                Boolean result = stub.syncData(request)
                        .map(response -> {
                            if (response.getSuccess()) {
                                return true;
                            } else {
                                log.error("Node {} rejected {} {}: {}", node.nodeId, entityType, entityId,
                                        SensitiveMessageSanitizer.sanitize(response.getMessage()));
                                return false;
                            }
                        })
                        .onFailure().recoverWithItem(error -> {
                            log.error("Error pushing to node {}", node.nodeId, error);
                            removeCachedStub(node);
                            return false;
                        })
                        .await().atMost(java.time.Duration.ofSeconds(timeoutSeconds));

                if (result != null && result) {
                    updateSyncRecord(syncRecordId, "SUCCESS", null);
                    return true;
                }
            } catch (Exception e) {
                log.error("Failed attempt to push {} {} to node {}", entityType, entityId, node.nodeId, e);
                removeCachedStub(node);
            }

            attempt = attempt + 1;
            if (attempt < maxAttempts) {
                long powerOfTwo = 1L;
                for (int i = 0; i < attempt; i++) {
                    powerOfTwo = powerOfTwo * 2L;
                }
                long delay = baseDelayMs * powerOfTwo;
                double randomVal = Math.random();
                long jitter = (long) (randomVal * 500.0);
                long totalDelay = delay + jitter;

                log.info("Retrying push {} {} to node {} in {} ms (attempt {}/{})",
                        entityType, entityId, node.nodeId, totalDelay, attempt, maxAttempts);
                try {
                    Thread.sleep(totalDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        updateSyncRecord(syncRecordId, "FAILED", "达到最大重试次数后仍未同步成功");
        return false;
    }

    public SyncProgress getSyncStatus(String nodeId) {
        return syncProgressMap.get(nodeId);
    }

    private long parseEntityId(String entityId) {
        try {
            return Long.parseLong(entityId);
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean canSyncPostToEdge(Post post) {
        if (post == null) {
            return false;
        }
        if (post.deletedAt != null) {
            return false;
        }
        if (post.status != PostStatus.PUBLISHED) {
            return false;
        }
        if (post.visibility != 0) {
            return false;
        }
        return post.publishedRevision != null;
    }

    private Long createSyncRecord(EdgeNode node, String entityType, String action, String entityId, int retryCount) {
        final Long[] syncRecordIdHolder = new Long[1];
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(new Runnable() {
            @Override
            public void run() {
                EdgeSyncRecord syncRecord = new EdgeSyncRecord();
                syncRecord.nodeId = resolveNodeId(node);
                syncRecord.entityType = entityType;
                syncRecord.entityId = entityId;
                syncRecord.action = action;
                syncRecord.status = "PENDING";
                syncRecord.retryCount = retryCount;
                syncRecord.createdAt = java.time.OffsetDateTime.now();
                syncRecord.updatedAt = syncRecord.createdAt;
                syncRecord.persist();
                syncRecordIdHolder[0] = syncRecord.id;
            }
        });
        return syncRecordIdHolder[0];
    }

    private void updateSyncRecord(Long syncRecordId, String status, String errorMessage) {
        if (syncRecordId == null) {
            return;
        }

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(new Runnable() {
            @Override
            public void run() {
                EdgeSyncRecord syncRecord = EdgeSyncRecord.findById(syncRecordId);
                if (syncRecord == null) {
                    return;
                }
                syncRecord.status = status;
                syncRecord.errorMessage = truncateErrorMessage(errorMessage);
                syncRecord.updatedAt = java.time.OffsetDateTime.now();
            }
        });
    }

    private String resolveNodeId(EdgeNode node) {
        if (node == null) {
            return "unknown";
        }
        if (node.nodeId == null || node.nodeId.isBlank()) {
            return "unknown";
        }
        return node.nodeId;
    }

    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        String sanitized = SensitiveMessageSanitizer.sanitize(errorMessage);
        if (sanitized.length() <= 1000) {
            return sanitized;
        }
        return sanitized.substring(0, 1000);
    }

    private boolean containsSecret(String key, JsonNode value) {
        if (key != null && isSecretName(key)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            java.util.Iterator<String> fields = value.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (isSecretName(field) || containsSecret(field, value.get(field))) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsSecret(key, item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSecretName(String value) {
        String normalized = value.toLowerCase();
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("api_key")
                || normalized.endsWith("_key");
    }

    public record SyncProgress(int total, int processed, String status, String lastError) {
    }
}
