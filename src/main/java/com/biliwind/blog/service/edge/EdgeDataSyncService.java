package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto;
import com.biliwind.blog.edge.MutinyEdgeNodeServiceGrpc;
import com.biliwind.blog.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EdgeDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(EdgeDataSyncService.class);
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

    void onStart(@Observes StartupEvent ev) {
        log.info("EdgeDataSyncService started");
        // 启动时尝试同步一次公钥到所有节点
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
        log.info("Detected post change, syncing to edge nodes: {}", event.getPostId());
        syncPost(event.getPostId());
    }

    /**
     * 监听通用同步事件（如标签）
     */
    public void onDataChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) DataSyncEvent event) {
        log.info("Detected {} change, action: {}, id: {}", event.getEntityType(), event.getAction(), event.getEntityId());

        if ("TAG".equals(event.getEntityType())) {
            syncTag(event.getEntityId(), event.getAction());
        } else if ("CATEGORY".equals(event.getEntityType())) {
            syncCategory(event.getEntityId(), event.getAction());
        } else if ("LINK".equals(event.getEntityType())) {
            syncLink(event.getEntityId(), event.getAction());
        } else if ("MEDIA".equals(event.getEntityType())) {
            syncMedia(event.getEntityId(), event.getAction());
        }
    }

    private void syncCategory(Long categoryId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Category category = Category.findById(categoryId);
            if (category == null) return;
            try {
                payload = objectMapper.writeValueAsString(category);
            } catch (Exception e) {
                log.error("Failed to serialize category: {}", e.getMessage());
                return;
            }
        }
        broadcastSync("CATEGORY", action, categoryId.toString(), payload);
    }

    private void syncLink(Long linkId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Link link = Link.findById(linkId);
            if (link == null) return;
            try {
                payload = objectMapper.writeValueAsString(link);
            } catch (Exception e) {
                log.error("Failed to serialize link: {}", e.getMessage());
                return;
            }
        }
        broadcastSync("LINK", action, linkId.toString(), payload);
    }

    private void syncMedia(Long mediaId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Media media = Media.findById(mediaId);
            if (media == null) return;
            try {
                payload = objectMapper.writeValueAsString(media);
            } catch (Exception e) {
                log.error("Failed to serialize media: {}", e.getMessage());
                return;
            }
        }
        broadcastSync("MEDIA", action, mediaId.toString(), payload);
    }

    private void syncTag(Long tagId, String action) {
        String payload = "{}";
        if ("UPSERT".equals(action)) {
            Tag tag = Tag.findById(tagId);
            if (tag == null) return;
            try {
                payload = objectMapper.writeValueAsString(tag);
            } catch (Exception e) {
                log.error("Failed to serialize tag: {}", e.getMessage());
                return;
            }
        }

        broadcastSync("TAG", action, tagId.toString(), payload);
    }

    private void syncPost(Long postId) {
        Post post = Post.findById(postId);
        String action = "UPSERT";
        String payload = "{}";

        if (post == null || post.deletedAt != null) {
            action = "DELETE";
        } else {
            try {
                // 构建包含关联数据的 Bundle
                Map<String, Object> bundle = new HashMap<>();
                bundle.put("post", post);

                // 包含当前版本
                if (post.currentRevision != null) {
                    bundle.put("currentRevision", post.currentRevision);
                }

                // 包含标签关联
                List<PostTag> postTags = PostTag.find("post.id = ?1", postId).list();
                java.util.ArrayList<Tag> tags = new java.util.ArrayList<>();
                for (PostTag postTag : postTags) {
                    tags.add(postTag.tag);
                }
                bundle.put("tags", tags);

                payload = objectMapper.writeValueAsString(bundle);
            } catch (Exception e) {
                log.error("Failed to serialize post bundle: {}", e.getMessage());
                return;
            }
        }

        broadcastSync("POST", action, postId.toString(), payload);
    }

    private void broadcastSync(String entityType, String action, String entityId, String payload) {
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
        pushToNodeAsync(node, entityType, action, entityId, payload).subscribe().with(
                success -> {
                    if (success) {
                        log.debug("Successfully pushed {} {} to node {}", entityType, entityId, node.nodeId);
                    }
                },
                error -> log.error("Failed to push {} {} to node {}", entityType, entityId, node.nodeId, error)
        );
    }

    private Uni<Boolean> pushToNodeAsync(EdgeNode node, String entityType, String action, String entityId, String payload) {
        String grpcAddress = resolveGrpcAddress(node);
        if (grpcAddress == null || grpcAddress.isEmpty()) {
            return Uni.createFrom().item(false);
        }

        try {
            MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = getStub(node);

            EdgeServiceProto.SyncDataRequest request = EdgeServiceProto.SyncDataRequest.newBuilder()
                    .setEntityType(entityType)
                    .setAction(action)
                    .setPayload(payload)
                    .setEntityId(parseEntityId(entityId))
                    .build();

            return stub.syncData(request)
                    .map(response -> {
                        if (response.getSuccess()) {
                            return true;
                        } else {
                            log.error("Node {} rejected {} {}: {}", node.nodeId, entityType, entityId, response.getMessage());
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
                        self.get().performFullSyncInternal(id, force);
                    } catch (Exception e) {
                        log.error("Unhandled error in full sync thread for node {}: {}", id, e.getMessage(), e);
                        syncProgressMap.put(id, new SyncProgress(0, 0, "FAILED", "Internal error: " + e.getMessage()));
                    }
                });
    }

    /**
     * 实际执行同步的内部方法，运行在独立事务中
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    public void performFullSyncInternal(String nodeId, boolean force) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) return;

        try {
            log.info("Performing full sync internal for node {}", nodeId);

            List<Tag> tags = Tag.listAll();
            List<Category> categories = Category.listAll();
            List<Link> links = Link.listAll();
            List<Media> mediaList = Media.listAll();
            List<Post> posts = Post.list("deletedAt is null");
            int total = tags.size() + categories.size() + links.size() + mediaList.size() + posts.size();

            syncProgressMap.put(nodeId, new SyncProgress(total, 0, "SYNCING", "Syncing tags..."));

            int processed = 0;
            int failedCount = 0;

            // 1. 同步标签
            for (Tag tag : tags) {
                try {
                    String tagPayload = objectMapper.writeValueAsString(tag);
                    String action = force ? "FORCE_UPSERT" : "UPSERT";
                    boolean success = pushToNodeWithRetry(node, "TAG", action, tag.id.toString(), tagPayload, 15);

                    if (!success) {
                        failedCount++;
                        log.warn("Failed to push tag {} to node {}", tag.id, nodeId);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error syncing tag {} to node {}: {}", tag.id, nodeId, e.getMessage());
                }
                processed++;
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing tags (" + processed + "/" + total + ")"));
            }

            // 1.1 同步分类
            syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing categories..."));
            for (Category category : categories) {
                try {
                    String categoryPayload = objectMapper.writeValueAsString(category);
                    String action = force ? "FORCE_UPSERT" : "UPSERT";
                    boolean success = pushToNodeWithRetry(node, "CATEGORY", action, category.id.toString(), categoryPayload, 15);
                    if (!success) {
                        failedCount++;
                        log.warn("Failed to push category {} to node {}", category.id, nodeId);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error syncing category {} to node {}: {}", category.id, nodeId, e.getMessage());
                }
                processed++;
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing categories (" + processed + "/" + total + ")"));
            }

            // 1.2 同步链接
            syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing links..."));
            for (Link link : links) {
                try {
                    String linkPayload = objectMapper.writeValueAsString(link);
                    String action = force ? "FORCE_UPSERT" : "UPSERT";
                    boolean success = pushToNodeWithRetry(node, "LINK", action, link.id.toString(), linkPayload, 15);
                    if (!success) {
                        failedCount++;
                        log.warn("Failed to push link {} to node {}", link.id, nodeId);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error syncing link {} to node {}: {}", link.id, nodeId, e.getMessage());
                }
                processed++;
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing links (" + processed + "/" + total + ")"));
            }

            // 1.3 同步媒体元数据
            syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing media metadata..."));
            for (Media media : mediaList) {
                try {
                    String mediaPayload = objectMapper.writeValueAsString(media);
                    String action = force ? "FORCE_UPSERT" : "UPSERT";
                    boolean success = pushToNodeWithRetry(node, "MEDIA", action, media.id.toString(), mediaPayload, 15);
                    if (!success) {
                        failedCount++;
                        log.warn("Failed to push media {} to node {}", media.id, nodeId);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error syncing media {} to node {}: {}", media.id, nodeId, e.getMessage());
                }
                processed++;
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing media (" + processed + "/" + total + ")"));
            }

            // 2. 同步文章
            syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing posts..."));
            for (Post post : posts) {
                try {
                    Map<String, Object> bundle = new HashMap<>();
                    bundle.put("post", post);
                    if (post.currentRevision != null) {
                        bundle.put("currentRevision", post.currentRevision);
                    }
                    List<PostTag> postTags = PostTag.find("post.id = ?1", post.id).list();
                    java.util.ArrayList<Tag> postTagEntities = new java.util.ArrayList<>();
                    for (PostTag postTag : postTags) {
                        postTagEntities.add(postTag.tag);
                    }
                    bundle.put("tags", postTagEntities);

                    String postPayload = objectMapper.writeValueAsString(bundle);
                    String action = force ? "FORCE_UPSERT" : "UPSERT";
                    boolean success = pushToNodeWithRetry(node, "POST", action, post.id.toString(), postPayload, 30);

                    if (!success) {
                        failedCount++;
                        log.warn("Failed to push post {} to node {}", post.id, nodeId);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error syncing post {} to node {}: {}", post.id, nodeId, e.getMessage());
                }
                processed++;
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "SYNCING", "Syncing posts (" + processed + "/" + total + ")"));
            }

            if (failedCount > 0) {
                String errorMsg = "Sync completed with " + failedCount + " failures out of " + total + " items";
                syncProgressMap.put(nodeId, new SyncProgress(total, processed, "COMPLETED_WITH_ERRORS", errorMsg));
                log.warn("Full sync completed for node {} with {} failures", nodeId, failedCount);
            } else {
                syncProgressMap.put(nodeId, new SyncProgress(total, total, "COMPLETED", null));
                log.info("Full sync completed for node {} successfully", nodeId);
            }
        } catch (Exception e) {
            log.error("Full sync execution failed for node {}: {}", nodeId, e.getMessage(), e);
            SyncProgress current = syncProgressMap.get(nodeId);
            syncProgressMap.put(nodeId, new SyncProgress(current != null ? current.total : 0,
                    current != null ? current.processed : 0, "FAILED", e.getMessage()));
        }
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
        String grpcAddress = resolveGrpcAddress(node);
        if (grpcAddress == null || grpcAddress.isEmpty()) {
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
                                log.error("Node {} rejected {} {}: {}", node.nodeId, entityType, entityId, response.getMessage());
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

    public record SyncProgress(int total, int processed, String status, String lastError) {
    }
}
