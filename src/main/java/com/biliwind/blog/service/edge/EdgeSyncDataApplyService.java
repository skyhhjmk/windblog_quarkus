package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.common.helper.RsaHelper;
import com.biliwind.blog.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class EdgeSyncDataApplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeSyncDataApplyService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Inject
    RsaHelper rsaHelper;

    @Transactional
    public void apply(SyncDataRequest request) {
        if (request == null) {
            return;
        }

        try {
            String action = request.getAction();
            String entityType = request.getEntityType();

            if (!isPublicSyncEntity(entityType)) {
                LOGGER.warn("拒绝非公开同步实体: {}", entityType);
                return;
            }

            if ("DELETE".equals(action)) {
                deleteEntity(entityType, request.getEntityId());
                return;
            }

            if (!"UPSERT".equals(action) && !"FORCE_UPSERT".equals(action) && !"UPDATE".equals(action)) {
                LOGGER.warn("忽略未知同步动作: {}", action);
                return;
            }

            upsertEntity(entityType, request.getPayload());
        } catch (Exception exception) {
            LOGGER.error("应用从主节点收到的同步数据失败: {} {}", request.getEntityType(), request.getEntityId(), exception);
        }
    }

    private boolean isPublicSyncEntity(String entityType) {
        return "TAG".equals(entityType)
                || "CATEGORY".equals(entityType)
                || "MEDIA".equals(entityType)
                || "POST".equals(entityType)
                || "CLUSTER_PUBLIC_KEY".equals(entityType);
    }

    private void upsertEntity(String entityType, String payload) throws Exception {
        if ("TAG".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Tag.class));
            return;
        }
        if ("CATEGORY".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Category.class));
            return;
        }
        if ("MEDIA".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Media.class));
            return;
        }
        if ("POST".equals(entityType)) {
            upsertPostBundle(payload);
            return;
        }
        if ("CLUSTER_PUBLIC_KEY".equals(entityType)) {
            rsaHelper.setClusterPublicKey(payload);
            return;
        }
        LOGGER.warn("忽略暂不支持的同步实体类型: {}", entityType);
    }

    private void upsertPostBundle(String payload) throws Exception {
        JsonNode rootNode = objectMapper.readTree(payload);
        JsonNode postNode = rootNode.get("post");
        if (postNode == null || postNode.isNull()) {
            return;
        }

        Post incomingPost = objectMapper.treeToValue(postNode, Post.class);
        if (incomingPost.id == null) {
            return;
        }

        Post existingPost = Post.findById(incomingPost.id);
        if (existingPost != null && existingPost.version != null && incomingPost.version != null) {
            if (existingPost.version.intValue() > incomingPost.version.intValue()) {
                return;
            }
        }

        entityManager.merge(incomingPost);

        JsonNode currentRevisionNode = rootNode.get("currentRevision");
        if (currentRevisionNode != null && !currentRevisionNode.isNull()) {
            PostRevision incomingRevision = objectMapper.treeToValue(currentRevisionNode, PostRevision.class);
            if (incomingRevision.id != null) {
                entityManager.merge(incomingRevision);
            }
        }
    }

    private void mergeEntity(Object entity) {
        entityManager.merge(entity);
    }

    private void deleteEntity(String entityType, long entityId) {
        if (entityId <= 0) {
            return;
        }

        if ("TAG".equals(entityType)) {
            Tag.deleteById(entityId);
            return;
        }
        if ("CATEGORY".equals(entityType)) {
            Category.deleteById(entityId);
            return;
        }
        if ("MEDIA".equals(entityType)) {
            Media.deleteById(entityId);
            return;
        }
        if ("POST".equals(entityType)) {
            Post.deleteById(entityId);
        }
    }
}
