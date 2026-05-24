package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
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

    @Transactional
    public void apply(SyncDataRequest request) {
        if (request == null) {
            return;
        }

        try {
            String action = request.getAction();
            String entityType = request.getEntityType();

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

    private void upsertEntity(String entityType, String payload) throws Exception {
        if ("USER".equals(entityType)) {
            upsertUser(payload);
            return;
        }
        if ("SYSTEM_SETTING".equals(entityType)) {
            upsertSystemSetting(payload);
            return;
        }
        if ("TAG".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Tag.class));
            return;
        }
        if ("CATEGORY".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Category.class));
            return;
        }
        if ("LINK".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Link.class));
            return;
        }
        if ("MEDIA".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, Media.class));
            return;
        }
        if ("AFFILIATE_LINK".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, AffiliateLink.class));
            return;
        }
        if ("REPOST_LICENSE".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, RepostLicense.class));
            return;
        }
        if ("AFFILIATE_TOKEN".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, AffiliateToken.class));
            return;
        }
        if ("BLOCKED_DOMAIN".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, BlockedDomain.class));
            return;
        }
        if ("RISK_DEVICE".equals(entityType)) {
            mergeEntity(objectMapper.readValue(payload, RiskDevice.class));
            return;
        }
        if ("POST".equals(entityType)) {
            upsertPostBundle(payload);
            return;
        }
        if ("CLUSTER_PUBLIC_KEY".equals(entityType)) {
            return;
        }
        LOGGER.warn("忽略暂不支持的同步实体类型: {}", entityType);
    }

    private void upsertUser(String payload) throws Exception {
        User incomingUser = objectMapper.readValue(payload, User.class);
        if (incomingUser.id == null) {
            return;
        }

        User existingUser = User.findById(incomingUser.id);
        if (existingUser == null) {
            entityManager.merge(incomingUser);
            return;
        }

        existingUser.username = incomingUser.username;
        existingUser.email = incomingUser.email;
        existingUser.password = incomingUser.password;
        existingUser.status = incomingUser.status;
        existingUser.roleName = incomingUser.roleName;
        existingUser.nickname = incomingUser.nickname;
        existingUser.avatar = incomingUser.avatar;
        existingUser.phone = incomingUser.phone;
        existingUser.extraInfo = incomingUser.extraInfo;
        existingUser.walletId = incomingUser.walletId;
        existingUser.level = incomingUser.level;
        existingUser.exp = incomingUser.exp;
        existingUser.backpackCapacity = incomingUser.backpackCapacity;
        existingUser.deletedAt = incomingUser.deletedAt;
    }

    private void upsertSystemSetting(String payload) throws Exception {
        SystemSetting incomingSetting = objectMapper.readValue(payload, SystemSetting.class);
        if (incomingSetting.configKey == null || incomingSetting.configKey.isBlank()) {
            return;
        }

        SystemSetting existingSetting = SystemSetting.findByKey(incomingSetting.configKey);
        if (existingSetting == null) {
            entityManager.merge(incomingSetting);
            return;
        }

        if (existingSetting.version != null && incomingSetting.version != null) {
            if (existingSetting.version.intValue() > incomingSetting.version.intValue()) {
                return;
            }
        }

        existingSetting.configValue = incomingSetting.configValue;
        existingSetting.configType = incomingSetting.configType;
        existingSetting.groupName = incomingSetting.groupName;
        existingSetting.uiSchema = incomingSetting.uiSchema;
        existingSetting.description = incomingSetting.description;
        existingSetting.version = incomingSetting.version;
        existingSetting.isFrozen = incomingSetting.isFrozen;
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

        if ("USER".equals(entityType)) {
            User.deleteById(entityId);
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
        if ("LINK".equals(entityType)) {
            Link.deleteById(entityId);
            return;
        }
        if ("MEDIA".equals(entityType)) {
            Media.deleteById(entityId);
            return;
        }
        if ("AFFILIATE_LINK".equals(entityType)) {
            AffiliateLink.deleteById(entityId);
            return;
        }
        if ("REPOST_LICENSE".equals(entityType)) {
            RepostLicense.deleteById(entityId);
            return;
        }
        if ("AFFILIATE_TOKEN".equals(entityType)) {
            AffiliateToken.deleteById(entityId);
            return;
        }
        if ("BLOCKED_DOMAIN".equals(entityType)) {
            BlockedDomain.deleteById(entityId);
            return;
        }
        if ("RISK_DEVICE".equals(entityType)) {
            RiskDevice.deleteById(entityId);
            return;
        }
        if ("POST".equals(entityType)) {
            Post.deleteById(entityId);
        }
    }
}
