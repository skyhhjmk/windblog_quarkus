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

    @Transactional
    public SyncDataResponse processSync(SyncDataRequest request) {
        try {
            String type = request.getEntityType();
            String action = request.getAction();
            String payload = request.getPayload();

            if ("TAG".equals(type)) {
                handleTagSync(action, payload);
            } else if ("POST".equals(type)) {
                handlePostSync(action, payload);
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

    private void handleTagSync(String action, String payload) throws Exception {
        Tag incomingTag = objectMapper.readValue(payload, Tag.class);
        if ("DELETE".equals(action)) {
            Tag.deleteById(incomingTag.id);
            log.info("Deleted tag: {}", incomingTag.id);
        } else {
            Tag existing = entityManager.find(Tag.class, incomingTag.id);
            if (existing != null) {
                existing.slug = incomingTag.slug;
                existing.name = incomingTag.name;
                existing.description = incomingTag.description;
                existing.updatedAt = incomingTag.updatedAt;
            } else {
                entityManager.merge(incomingTag);
                entityManager.flush();
            }
            log.info("Synced tag: {}", incomingTag.id);
        }
    }

    private void handlePostSync(String action, String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);

        if ("DELETE".equals(action)) {
            Long postId;
            if (root.has("post") && root.get("post").has("id")) {
                postId = root.get("post").get("id").asLong();
            } else {
                postId = root.asLong();
            }
            Post.deleteById(postId);
            log.info("Deleted post: {}", postId);
        } else {
            JsonNode postNode = root.get("post");
            Post incomingPost = objectMapper.treeToValue(postNode, Post.class);

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
            entityManager.merge(incomingCategory);
            entityManager.flush();
            existingCat = entityManager.find(Category.class, incomingCategory.id);
        }
        return existingCat;
    }
}
