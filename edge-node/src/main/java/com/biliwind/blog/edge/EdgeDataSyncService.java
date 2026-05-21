package com.biliwind.blog.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.edge.EdgeServiceProto.SyncDataResponse;
import com.biliwind.blog.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class EdgeDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(EdgeDataSyncService.class);

    @Inject
    EntityManager entityManager;
    @Inject
    ObjectMapper objectMapper;
    @Inject
    com.biliwind.blog.common.helper.RsaHelper rsaHelper;
    @Inject
    EdgeCacheService edgeCacheService;

    @Transactional
    public SyncDataResponse processSync(SyncDataRequest request) {
        try {
            String type = request.getEntityType();
            String action = request.getAction();
            String payload = request.getPayload();
            Long entityId = request.getEntityId();

            if ("TAG".equals(type)) {
                handleTagSync(action, payload, entityId);
            } else if ("POST".equals(type)) {
                handlePostSync(action, payload, entityId);
            } else if ("CATEGORY".equals(type)) {
                handleCategorySync(action, payload, entityId);
            } else if ("LINK".equals(type)) {
                handleLinkSync(action, payload, entityId);
            } else if ("MEDIA".equals(type)) {
                handleMediaSync(action, payload, entityId);
            } else if ("CLUSTER_PUBLIC_KEY".equals(type)) {
                handleClusterKeySync(payload);
            } else {
                return SyncDataResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Unsupported entity type: " + type)
                        .build();
            }

            // 显式刷新以确保异常在事务内抛出
            Tag.getEntityManager().flush();

            return SyncDataResponse.newBuilder()
                    .setSuccess(true)
                    .build();
        } catch (Exception e) {
            log.error("Failed to process sync: {}", e.getMessage(), e);
            return SyncDataResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }

    private void handleTagSync(String action, String payload, Long entityId) throws Exception {
        if ("DELETE".equals(action)) {
            Tag.deleteById(entityId);
            edgeCacheService.invalidatePublicListCaches();
            log.info("Deleted tag: {}", entityId);
        } else {
            Tag incomingTag = objectMapper.readValue(payload, Tag.class);
            if ("FORCE_UPSERT".equals(action)) {
                Tag.deleteById(incomingTag.id);
                entityManager.flush();
            }
            Tag existing = entityManager.find(Tag.class, incomingTag.id);
            if (existing != null) {
                existing.slug = incomingTag.slug;
                existing.name = incomingTag.name;
                existing.description = incomingTag.description;
                existing.createdAt = incomingTag.createdAt;
                existing.updatedAt = incomingTag.updatedAt;
            } else {
                entityManager.merge(incomingTag);
                entityManager.flush();
            }
            log.info("Synced tag: {}", incomingTag.id);
            edgeCacheService.invalidatePublicListCaches();
        }
    }

    private void handlePostSync(String action, String payload, Long entityId) throws Exception {
        if ("DELETE".equals(action)) {
            Post existingPost = entityManager.find(Post.class, entityId);
            String oldSlug = null;
            if (existingPost != null) {
                oldSlug = existingPost.slug;
            }
            Post.deleteById(entityId);
            if (oldSlug != null) {
                edgeCacheService.invalidatePost(oldSlug);
            }
            edgeCacheService.invalidatePublicListCaches();
            log.info("Deleted post: {}", entityId);
        } else {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode postNode = root.get("post");
            Post incomingPost = objectMapper.treeToValue(postNode, Post.class);
            Post oldPost = entityManager.find(Post.class, incomingPost.id);
            String oldSlug = null;
            if (oldPost != null) {
                oldSlug = oldPost.slug;
            }

            if ("FORCE_UPSERT".equals(action)) {
                Post.deleteById(incomingPost.id);
                entityManager.flush();
            }

            // 0. 置空可能引起级联问题的关联字段，修订版在后续独立处理
            incomingPost.currentRevision = null;

            // 1. 处理用户依赖
            if (incomingPost.user != null) {
                incomingPost.user = syncUserDependency(incomingPost.user);
            }

            // 2. 处理分类依赖
            if (incomingPost.category != null) {
                incomingPost.category = syncCategoryDependency(incomingPost.category);
            }

            // 3. 持久化 Post
            Post existingPost = entityManager.find(Post.class, incomingPost.id);
            if (existingPost != null) {
                existingPost.slug = incomingPost.slug;
                existingPost.title = incomingPost.title;
                existingPost.summary = incomingPost.summary;
                existingPost.aiSummary = incomingPost.aiSummary;
                existingPost.aiSummaryStatus = incomingPost.aiSummaryStatus;
                existingPost.status = incomingPost.status;
                existingPost.visibility = incomingPost.visibility;
                existingPost.password = incomingPost.password;
                existingPost.seoTitle = incomingPost.seoTitle;
                existingPost.seoKeywords = incomingPost.seoKeywords;
                existingPost.seoDescription = incomingPost.seoDescription;
                existingPost.renderType = incomingPost.renderType;
                existingPost.publishedAt = incomingPost.publishedAt;
                existingPost.createdAt = incomingPost.createdAt;
                existingPost.updatedAt = incomingPost.updatedAt;
                existingPost.visibilityRegions = incomingPost.visibilityRegions;
                existingPost.featured = incomingPost.featured;
                existingPost.allowComment = incomingPost.allowComment;
                existingPost.extraInfo = incomingPost.extraInfo;
                existingPost.user = incomingPost.user;
                existingPost.category = incomingPost.category;
            } else {
                // 强制同步：置空版本号以规避主从节点间的乐观锁冲突
                incomingPost.version = null;
                entityManager.merge(incomingPost);
                entityManager.flush();
                existingPost = entityManager.find(Post.class, incomingPost.id);
            }

            // 4. 同步 Revision
            if (root.has("currentRevision")) {
                PostRevision incomingRev = objectMapper.treeToValue(root.get("currentRevision"), PostRevision.class);

                // 确保修订版的创建者存在
                if (incomingRev.createdBy != null) {
                    incomingRev.createdBy = syncUserDependency(incomingRev.createdBy);
                }

                PostRevision existingRev = entityManager.find(PostRevision.class, incomingRev.id);
                if (existingRev == null) {
                    incomingRev.post = existingPost;
                    entityManager.merge(incomingRev);
                    entityManager.flush();
                    existingRev = entityManager.find(PostRevision.class, incomingRev.id);
                } else {
                    existingRev.post = existingPost;
                    existingRev.title = incomingRev.title;
                    existingRev.contentMarkdown = incomingRev.contentMarkdown;
                }
                existingPost.currentRevision = existingRev;
            }

            // 5. 同步 Tags 关联
            if (root.has("tags")) {
                JsonNode tagsNode = root.get("tags");
                List<Tag> incomingTags = new ArrayList<>();
                for (JsonNode tNode : tagsNode) {
                    Tag t = objectMapper.treeToValue(tNode, Tag.class);
                    Tag existingTag = entityManager.find(Tag.class, t.id);
                    if (existingTag == null) {
                        entityManager.merge(t);
                        entityManager.flush();
                        existingTag = entityManager.find(Tag.class, t.id);
                    }
                    incomingTags.add(existingTag);
                }

                PostTag.delete("post.id = ?1", existingPost.id);
                for (Tag tag : incomingTags) {
                    PostTag pt = new PostTag();
                    pt.id = new PostTagId(existingPost.id, tag.id);
                    pt.post = existingPost;
                    pt.tag = tag;
                    pt.persist();
                }
            }

            log.info("Successfully synced post bundle: {}", existingPost.id);
            if (oldSlug != null && !oldSlug.equals(existingPost.slug)) {
                edgeCacheService.invalidatePost(oldSlug);
            }
            edgeCacheService.setPost(existingPost);
            edgeCacheService.invalidatePublicListCaches();
        }
    }

    private User syncUserDependency(User incomingUser) {
        User existingUser = entityManager.find(User.class, incomingUser.id);
        if (existingUser == null) {
            log.warn("User {} not found on edge node, attempting to sync user first", incomingUser.id);
            // 清空可能缺失的关联字段
            incomingUser.roleName = null;
            incomingUser.walletId = null;
            entityManager.merge(incomingUser);
            entityManager.flush();
            existingUser = entityManager.find(User.class, incomingUser.id);
        }
        return existingUser;
    }

    private Category syncCategoryDependency(Category incomingCategory) {
        Category existingCat = entityManager.find(Category.class, incomingCategory.id);
        if (existingCat == null) {
            log.warn("Category {} not found on edge node, attempting to sync category first", incomingCategory.id);
            // 处理父分类依赖
            if (incomingCategory.parent != null) {
                incomingCategory.parent = syncCategoryDependency(incomingCategory.parent);
            }
            entityManager.merge(incomingCategory);
            entityManager.flush();
            existingCat = entityManager.find(Category.class, incomingCategory.id);
        }
        return existingCat;
    }

    private void handleCategorySync(String action, String payload, Long entityId) throws Exception {
        if ("DELETE".equals(action)) {
            Category.deleteById(entityId);
            edgeCacheService.invalidatePublicListCaches();
            log.info("Deleted category: {}", entityId);
        } else {
            Category incoming = objectMapper.readValue(payload, Category.class);
            if ("FORCE_UPSERT".equals(action)) {
                Category.deleteById(incoming.id);
                entityManager.flush();
            }
            if (incoming.parent != null) {
                incoming.parent = syncCategoryDependency(incoming.parent);
            }
            Category existing = entityManager.find(Category.class, incoming.id);
            if (existing != null) {
                existing.slug = incoming.slug;
                existing.name = incoming.name;
                existing.description = incoming.description;
                existing.path = incoming.path;
                existing.postCount = incoming.postCount;
                existing.parent = incoming.parent;
                existing.createdAt = incoming.createdAt;
                existing.updatedAt = incoming.updatedAt;
            } else {
                entityManager.merge(incoming);
            }
            log.info("Synced category: {}", incoming.id);
            edgeCacheService.invalidatePublicListCaches();
        }
    }

    private void handleLinkSync(String action, String payload, Long entityId) throws Exception {
        if ("DELETE".equals(action)) {
            Link.deleteById(entityId);
            edgeCacheService.invalidatePublicListCaches();
            log.info("Deleted link: {}", entityId);
        } else {
            Link incoming = objectMapper.readValue(payload, Link.class);
            if ("FORCE_UPSERT".equals(action)) {
                Link.deleteById(incoming.id);
                entityManager.flush();
            }
            Link existing = entityManager.find(Link.class, incoming.id);
            if (existing != null) {
                existing.name = incoming.name;
                existing.url = incoming.url;
                existing.description = incoming.description;
                existing.image = incoming.image;
                existing.icon = incoming.icon;
                existing.sortOrder = incoming.sortOrder;
                existing.type = incoming.type;
                existing.status = incoming.status;
                existing.target = incoming.target;
                existing.redirectType = incoming.redirectType;
                existing.showUrl = incoming.showUrl;
                existing.content = incoming.content;
                existing.email = incoming.email;
                existing.callbackUrl = incoming.callbackUrl;
                existing.note = incoming.note;
                existing.seoTitle = incoming.seoTitle;
                existing.seoKeywords = incoming.seoKeywords;
                existing.seoDescription = incoming.seoDescription;
                existing.settings = incoming.settings;
                existing.createdAt = incoming.createdAt;
                existing.updatedAt = incoming.updatedAt;
            } else {
                entityManager.merge(incoming);
            }
            log.info("Synced link: {}", incoming.id);
            edgeCacheService.invalidatePublicListCaches();
        }
    }

    private void handleMediaSync(String action, String payload, Long entityId) throws Exception {
        if ("DELETE".equals(action)) {
            Media existingMedia = entityManager.find(Media.class, entityId);
            String oldStorageKey = null;
            if (existingMedia != null) {
                oldStorageKey = existingMedia.storageKey;
            }
            Media.deleteById(entityId);
            if (oldStorageKey != null) {
                edgeCacheService.invalidateMedia(oldStorageKey);
            }
            log.info("Deleted media: {}", entityId);
        } else {
            Media incoming = objectMapper.readValue(payload, Media.class);
            Media oldMedia = entityManager.find(Media.class, incoming.id);
            String oldStorageKey = null;
            if (oldMedia != null) {
                oldStorageKey = oldMedia.storageKey;
            }
            if ("FORCE_UPSERT".equals(action)) {
                Media.deleteById(incoming.id);
                entityManager.flush();
            }
            Media existing = entityManager.find(Media.class, incoming.id);
            if (existing != null) {
                existing.storageKey = incoming.storageKey;
                existing.url = incoming.url;
                existing.mediaType = incoming.mediaType;
                existing.mimeType = incoming.mimeType;
                existing.fileName = incoming.fileName;
                existing.size = incoming.size;
                existing.uploadedBy = incoming.uploadedBy;
                existing.width = incoming.width;
                existing.height = incoming.height;
                existing.alt = incoming.alt;
                existing.metadata = incoming.metadata;
                existing.storageProviders = incoming.storageProviders;
                existing.processingStatus = incoming.processingStatus;
                existing.processingProgress = incoming.processingProgress;
                existing.processingError = incoming.processingError;
                existing.visibilityRegions = incoming.visibilityRegions;
                existing.createdAt = incoming.createdAt;
                existing.updatedAt = incoming.updatedAt;
                existing.deletedAt = incoming.deletedAt;
            } else {
                entityManager.merge(incoming);
            }
            log.info("Synced media: {}", incoming.id);
            if (oldStorageKey != null && !oldStorageKey.equals(incoming.storageKey)) {
                edgeCacheService.invalidateMedia(oldStorageKey);
            }
            Media syncedMedia = entityManager.find(Media.class, incoming.id);
            edgeCacheService.setMedia(syncedMedia);
        }
    }

    private void handleClusterKeySync(String payload) {
        log.info("Received cluster public key update");
        rsaHelper.setClusterPublicKey(payload);
    }
}
