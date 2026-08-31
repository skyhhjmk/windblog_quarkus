package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.common.helper.RsaHelper;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;

@ApplicationScoped
public class EdgeSyncDataApplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeSyncDataApplyService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Inject
    RsaHelper rsaHelper;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

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

            upsertEntity(entityType, request.getEntityId(), request.getPayload());
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

    private void upsertEntity(String entityType, long entityId, String payload) throws Exception {
        if ("TAG".equals(entityType)) {
            upsertPublicTag(entityId, payload);
            return;
        }
        if ("CATEGORY".equals(entityType)) {
            upsertPublicCategory(entityId, payload);
            return;
        }
        if ("MEDIA".equals(entityType)) {
            upsertPublicMedia(entityId, payload);
            return;
        }
        if ("POST".equals(entityType)) {
            upsertPostBundle(entityId, payload);
            return;
        }
        if ("CLUSTER_PUBLIC_KEY".equals(entityType)) {
            rsaHelper.setClusterPublicKey(payload);
            return;
        }
        LOGGER.warn("忽略暂不支持的同步实体类型: {}", entityType);
    }

    private void upsertPublicTag(long entityId, String payload) throws Exception {
        JsonNode node = readEntityNode(entityId, payload, "标签");
        if (node == null) {
            return;
        }

        Tag tag = Tag.findById(entityId);
        boolean newTag = tag == null;
        if (newTag) {
            tag = new Tag();
            tag.id = entityId;
        }
        tag.slug = readRequiredText(node, "slug");
        tag.name = readRequiredStringMap(node, "name");
        tag.description = readStringMap(node, "description");
        if (newTag) {
            insertPublicTag(tag);
        }
    }

    private void upsertPublicCategory(long entityId, String payload) throws Exception {
        JsonNode node = readEntityNode(entityId, payload, "分类");
        if (node == null) {
            return;
        }

        Category category = Category.findById(entityId);
        boolean newCategory = category == null;
        if (newCategory) {
            category = new Category();
            category.id = entityId;
        }

        Long parentId = readReferenceId(node, "parent");
        category.parent = parentId == null ? null : entityManager.getReference(Category.class, parentId);
        category.slug = readRequiredText(node, "slug");
        category.name = readRequiredStringMap(node, "name");
        category.description = readStringMap(node, "description");
        category.path = readRequiredText(node, "path");
        category.postCount = readOptionalLong(node, "postCount");
        if (category.postCount == null) {
            category.postCount = 0L;
        }
        if (newCategory) {
            insertPublicCategory(category);
        }
    }

    private void insertPublicTag(Tag tag) throws Exception {
        entityManager.createNativeQuery("""
                insert into tags (id, slug, name, description, created_at, updated_at)
                values (:id, :slug, cast(:name as jsonb), cast(:description as jsonb),
                        current_timestamp, current_timestamp)
                """)
                .setParameter("id", tag.id)
                .setParameter("slug", tag.slug)
                .setParameter("name", writeJsonValue(tag.name))
                .setParameter("description", writeJsonValue(tag.description))
                .executeUpdate();
        synchronizeSequence("tags");
    }

    private void insertPublicCategory(Category category) throws Exception {
        Long parentId = category.parent == null ? null : category.parent.id;
        entityManager.createNativeQuery("""
                insert into categories (id, parent_id, slug, name, description, path, created_at, updated_at, post_count)
                values (:id, :parentId, :slug, cast(:name as jsonb), cast(:description as jsonb),
                        cast(:path as ltree), current_timestamp, current_timestamp, :postCount)
                """)
                .setParameter("id", category.id)
                .setParameter("parentId", parentId)
                .setParameter("slug", category.slug)
                .setParameter("name", writeJsonValue(category.name))
                .setParameter("description", writeJsonValue(category.description))
                .setParameter("path", category.path)
                .setParameter("postCount", category.postCount)
                .executeUpdate();
        synchronizeSequence("categories");
    }

    private void synchronizeSequence(String tableName) {
        if (!List.of("tags", "categories", "media", "posts", "post_revisions").contains(tableName)) {
            throw new IllegalArgumentException("不允许同步未知序列");
        }
        String sql = "select setval(pg_get_serial_sequence('" + tableName
                + "', 'id'), (select max(id) from " + tableName + "), true)";
        entityManager.createNativeQuery(sql).getSingleResult();
    }

    private JsonNode readEntityNode(long entityId, String payload, String entityLabel) throws Exception {
        if (entityId <= 0) {
            return null;
        }
        JsonNode node = objectMapper.readTree(payload);
        if (node == null || !node.isObject() || readRequiredLong(node, "id") != entityId) {
            LOGGER.warn("忽略{}同步 ID 不匹配的公开快照: {}", entityLabel, entityId);
            return null;
        }
        return node;
    }

    /**
     * 将主节点公开媒体快照复制到边缘节点，避免把 JSON 直接反序列化到实体。
     * 删除标记、处理失败原因和 ORM 管理字段必须由边缘节点本地维护。
     */
    private void upsertPublicMedia(long entityId, String payload) throws Exception {
        if (entityId <= 0) {
            return;
        }

        JsonNode node = objectMapper.readTree(payload);
        if (node == null || !node.isObject() || readRequiredLong(node, "id") != entityId) {
            LOGGER.warn("忽略媒体同步 ID 不匹配的公开快照: {}", entityId);
            return;
        }

        Media media = Media.findById(entityId);
        if (media != null && media.deletedAt != null) {
            LOGGER.warn("忽略覆盖边缘本地已删除媒体的公开快照: {}", entityId);
            return;
        }

        Integer incomingVersion = readOptionalInteger(node, "version");
        if (media != null && media.version != null && incomingVersion != null
                && media.version.intValue() > incomingVersion.intValue()) {
            return;
        }

        boolean newMedia = media == null;
        if (newMedia) {
            media = new Media();
            media.id = entityId;
        }

        media.storageKey = readRequiredText(node, "storageKey");
        media.url = readRequiredText(node, "url");
        media.mediaType = readRequiredShort(node, "mediaType");
        media.mimeType = readOptionalText(node, "mimeType");
        media.fileName = readOptionalText(node, "fileName");
        media.size = readOptionalLong(node, "size");
        media.uploadedBy = readOptionalLong(node, "uploadedBy");
        media.width = readOptionalInteger(node, "width");
        media.height = readOptionalInteger(node, "height");
        media.alt = readStringMap(node, "alt");
        media.metadata = readPublicMetadata(node, "metadata");
        Map<String, Object> publicStorageClasses = readPublicStorageClasses(node, "storageClasses");
        media.storageClasses = publicStorageClasses == null ? new LinkedHashMap<>() : publicStorageClasses;
        media.visibilityRegions = readStringList(node, "visibilityRegions");
        media.hiddenRegions = readStringList(node, "hiddenRegions");
        media.syncStorageClasses = readStringList(node, "syncStorageClasses");
        media.skipStorageClasses = readStringList(node, "skipStorageClasses");
        media.processingStatus = readOptionalText(node, "processingStatus");
        media.processingProgress = readOptionalInteger(node, "processingProgress");
        String incomingVirusScanStatus = readOptionalText(node, "virusScanStatus");
        if (incomingVirusScanStatus != null && !incomingVirusScanStatus.isBlank()) {
            media.virusScanStatus = incomingVirusScanStatus;
        }
        if (node.has("virusScannedAt")) {
            media.virusScannedAt = readOptionalDateTime(node, "virusScannedAt");
        }
        if (node.has("virusScanMessage")) {
            media.virusScanMessage = readOptionalText(node, "virusScanMessage");
        }
        if (newMedia && (media.virusScanStatus == null || media.virusScanStatus.isBlank())) {
            media.virusScanStatus = "NOT_SCANNED";
        }

        if (media.processingProgress != null && (media.processingProgress < 0 || media.processingProgress > 100)) {
            throw new IllegalArgumentException("媒体处理进度超出范围");
        }

        if (newMedia) {
            insertPublicMedia(media);
        }
    }

    private void insertPublicMedia(Media media) throws Exception {
        entityManager.createNativeQuery("""
                insert into media (
                    id, storage_key, url, media_type, mime_type, file_name, size, uploaded_by,
                    width, height, alt, metadata, storage_classes, version, created_at, updated_at,
                    processing_status, processing_progress, visibility_regions, hidden_regions,
                    sync_storage_classes, skip_storage_classes, virus_scan_status, virus_scanned_at,
                    virus_scan_message
                ) values (
                    :id, :storageKey, :url, :mediaType, :mimeType, :fileName, :size, :uploadedBy,
                    :width, :height, cast(:alt as jsonb), cast(:metadata as jsonb),
                    cast(:storageClasses as jsonb), :version, current_timestamp, current_timestamp,
                    :processingStatus, :processingProgress, cast(:visibilityRegions as jsonb),
                    cast(:hiddenRegions as jsonb), cast(:syncStorageClasses as jsonb),
                    cast(:skipStorageClasses as jsonb), :virusScanStatus, :virusScannedAt,
                    :virusScanMessage
                )
                """)
                .setParameter("id", media.id)
                .setParameter("storageKey", media.storageKey)
                .setParameter("url", media.url)
                .setParameter("mediaType", media.mediaType)
                .setParameter("mimeType", media.mimeType)
                .setParameter("fileName", media.fileName)
                .setParameter("size", media.size)
                .setParameter("uploadedBy", media.uploadedBy)
                .setParameter("width", media.width)
                .setParameter("height", media.height)
                .setParameter("alt", writeJsonValue(media.alt))
                .setParameter("metadata", writeJsonValue(media.metadata))
                .setParameter("storageClasses", writeJsonValue(media.storageClasses))
                .setParameter("version", 0)
                .setParameter("processingStatus", media.processingStatus)
                .setParameter("processingProgress", media.processingProgress)
                .setParameter("visibilityRegions", writeJsonValue(media.visibilityRegions))
                .setParameter("hiddenRegions", writeJsonValue(media.hiddenRegions))
                .setParameter("syncStorageClasses", writeJsonValue(media.syncStorageClasses))
                .setParameter("skipStorageClasses", writeJsonValue(media.skipStorageClasses))
                .setParameter("virusScanStatus", media.virusScanStatus == null
                        ? "NOT_SCANNED" : media.virusScanStatus)
                .setParameter("virusScannedAt", media.virusScannedAt)
                .setParameter("virusScanMessage", media.virusScanMessage)
                .executeUpdate();

        entityManager.createNativeQuery("""
                select setval(
                    pg_get_serial_sequence('media', 'id'),
                    (select max(id) from media),
                    true
                )
                """).getSingleResult();
    }

    private String writeJsonValue(Object value) throws Exception {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("媒体公开快照缺少有效字段: " + fieldName);
        }
        return value.asText();
    }

    private String readOptionalText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        return value.asText();
    }

    private long readRequiredLong(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        return value.asLong();
    }

    private Long readOptionalLong(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        return value.asLong();
    }

    private short readRequiredShort(JsonNode node, String fieldName) {
        Integer value = readOptionalInteger(node, fieldName);
        if (value == null || value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        return value.shortValue();
    }

    private Integer readOptionalInteger(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        return value.asInt();
    }

    private Map<String, String> readStringMap(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        Map<String, String> result = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull() && !entry.getValue().isTextual()) {
                throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
            }
            result.put(entry.getKey(), entry.getValue().isNull() ? null : entry.getValue().asText());
        });
        return result;
    }

    private Map<String, String> readRequiredStringMap(JsonNode node, String fieldName) {
        Map<String, String> result = readStringMap(node, fieldName);
        if (result == null) {
            throw new IllegalArgumentException("公开同步快照缺少有效字段: " + fieldName);
        }
        return result;
    }

    private Long readReferenceId(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("公开同步引用字段类型无效: " + fieldName);
        }
        JsonNode idNode = value.get("id");
        if (idNode == null || !idNode.canConvertToLong() || idNode.asLong() <= 0) {
            throw new IllegalArgumentException("公开同步引用字段无效: " + fieldName);
        }
        return idNode.asLong();
    }

    private List<String> readStringList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
            }
            result.add(item.asText());
        }
        return result;
    }

    private Map<String, Object> readPublicMetadata(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String allowedField : List.of("placeholderUrl", "thumbnailUrl", "previewUrl", "webpUrl",
                "coverUrl", "width", "height")) {
            JsonNode allowedValue = value.get(allowedField);
            if (allowedValue != null && !allowedValue.isNull()) {
                if (!(allowedValue.isTextual() || allowedValue.isNumber())) {
                    throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
                }
                result.put(allowedField, objectMapper.convertValue(allowedValue, Object.class));
            }
        }
        return result;
    }

    private Map<String, Object> readPublicStorageClasses(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("媒体公开快照字段类型无效: " + fieldName);
        }

        Map<String, Object> providers = new LinkedHashMap<>();
        value.fields().forEachRemaining(providerEntry -> {
            JsonNode providerValue = providerEntry.getValue();
            if (!providerValue.isObject()) {
                throw new IllegalArgumentException("媒体公开存储类字段类型无效");
            }
            Map<String, Object> variants = new LinkedHashMap<>();
            providerValue.fields().forEachRemaining(variantEntry -> {
                JsonNode variantValue = variantEntry.getValue();
                if (!variantValue.isObject()) {
                    throw new IllegalArgumentException("媒体公开存储变体字段类型无效");
                }
                ObjectNode safeVariant = objectMapper.createObjectNode();
                copyScalar(variantValue, safeVariant, "status");
                copyScalar(variantValue, safeVariant, "path");
                copyScalar(variantValue, safeVariant, "size");
                variants.put(variantEntry.getKey(), objectMapper.convertValue(safeVariant, Object.class));
            });
            providers.put(providerEntry.getKey(), variants);
        });
        return providers;
    }

    private void copyScalar(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && (value.isTextual() || value.isNumber() || value.isBoolean())) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private void upsertPostBundle(long entityId, String payload) throws Exception {
        JsonNode rootNode = objectMapper.readTree(payload);
        JsonNode postNode = rootNode.get("post");
        if (postNode == null || postNode.isNull()) {
            return;
        }

        JsonNode validatedPostNode = readEntityNode(entityId, objectMapper.writeValueAsString(postNode), "文章");
        if (validatedPostNode == null) {
            return;
        }

        Post post = Post.findById(entityId);
        if (post != null && post.deletedAt != null) {
            LOGGER.warn("忽略覆盖边缘本地已删除文章的公开快照: {}", entityId);
            return;
        }

        Integer incomingVersion = readOptionalInteger(validatedPostNode, "version");
        if (post != null && post.version != null && incomingVersion != null
                && post.version.intValue() > incomingVersion.intValue()) {
            return;
        }

        boolean newPost = post == null;
        if (newPost) {
            post = new Post();
            post.id = entityId;
        }

        post.slug = readRequiredText(validatedPostNode, "slug");
        post.title = readRequiredStringMap(validatedPostNode, "title");
        post.summary = readStringMap(validatedPostNode, "summary");
        post.aiSummary = readStringMap(validatedPostNode, "aiSummary");
        Integer aiSummaryStatus = readOptionalInteger(validatedPostNode, "aiSummaryStatus");
        post.aiSummaryStatus = aiSummaryStatus == null ? 0 : aiSummaryStatus.shortValue();
        String statusName = readRequiredText(validatedPostNode, "status");
        post.status = PostStatus.valueOf(statusName);
        post.visibility = readRequiredShort(validatedPostNode, "visibility");
        post.seoTitle = readOptionalText(validatedPostNode, "seoTitle");
        post.seoKeywords = readOptionalText(validatedPostNode, "seoKeywords");
        post.seoDescription = readOptionalText(validatedPostNode, "seoDescription");
        post.renderType = PostRenderType.valueOf(readRequiredText(validatedPostNode, "renderType"));

        Long userId = readReferenceId(validatedPostNode, "user");
        if (userId == null) {
            throw new IllegalArgumentException("公开文章快照缺少作者引用");
        }
        post.user = entityManager.getReference(User.class, userId);
        Long categoryId = readReferenceId(validatedPostNode, "category");
        post.category = categoryId == null ? null : entityManager.getReference(Category.class, categoryId);
        post.extraInfo = readPublicExtraInfo(validatedPostNode, "extraInfo");
        post.visibilityRegions = readStringList(validatedPostNode, "visibilityRegions");
        post.contentDeclarations = readStringList(validatedPostNode, "contentDeclarations");
        String incomingRepostPolicyCode = readOptionalText(validatedPostNode, "repostPolicyCode");
        if (incomingRepostPolicyCode != null && !incomingRepostPolicyCode.isBlank()) {
            post.repostPolicyCode = repostPolicyCatalog.resolve(incomingRepostPolicyCode, null).code();
        } else {
            // Old edge snapshots do not carry the dedicated field. Preserve the
            // historical CC_BY_NC_4_0 declaration fallback when rebuilding them.
            post.repostPolicyCode = repostPolicyCatalog.resolve(null, post.contentDeclarations).code();
        }
        post.publishedAt = readOptionalDateTime(validatedPostNode, "publishedAt");
        post.viewCount = readOptionalLong(validatedPostNode, "viewCount");
        if (post.viewCount == null) {
            post.viewCount = 0L;
        }
        post.featured = readOptionalBoolean(validatedPostNode, "featured");
        if (post.featured == null) {
            post.featured = false;
        }
        post.allowComment = readOptionalBoolean(validatedPostNode, "allowComment");
        if (post.allowComment == null) {
            post.allowComment = true;
        }

        if (newPost) {
            insertPublicPost(post);
            post = Post.findById(entityId);
        }

        JsonNode currentRevisionNode = rootNode.get("currentRevision");
        if (currentRevisionNode != null && !currentRevisionNode.isNull()) {
            PostRevision revision = upsertPublicRevision(post, currentRevisionNode);
            post.publishedRevision = revision;
        } else {
            post.publishedRevision = null;
        }

    }

    private PostRevision upsertPublicRevision(Post post, JsonNode node) throws Exception {
        if (!node.isObject()) {
            throw new IllegalArgumentException("公开文章版本快照格式无效");
        }
        long revisionId = readRequiredLong(node, "id");
        PostRevision revision = PostRevision.findById(revisionId);
        boolean newRevision = revision == null;
        if (!newRevision && revision.post != null && !revision.post.id.equals(post.id)) {
            throw new IllegalArgumentException("公开文章版本引用了其他文章");
        }
        if (newRevision) {
            revision = new PostRevision();
            revision.id = revisionId;
        }
        revision.post = post;
        revision.title = readRequiredStringMap(node, "title");
        revision.contentMarkdown = readRequiredStringMap(node, "contentMarkdown");
        revision.editorType = readRequiredShort(node, "editorType");
        Integer revisionNumber = readOptionalInteger(node, "revisionNumber");
        if (revisionNumber == null) {
            throw new IllegalArgumentException("公开文章版本快照缺少版本号");
        }
        revision.revisionNumber = revisionNumber;
        Long createdById = readReferenceId(node, "createdBy");
        if (createdById == null) {
            throw new IllegalArgumentException("公开文章版本快照缺少创建人引用");
        }
        revision.createdBy = entityManager.getReference(User.class, createdById);
        if (newRevision) {
            insertPublicRevision(revision);
            return PostRevision.findById(revisionId);
        }
        return revision;
    }

    private void insertPublicPost(Post post) throws Exception {
        Long categoryId = post.category == null ? null : post.category.id;
        entityManager.createNativeQuery("""
                insert into posts (
                    id, slug, title, summary, ai_summary, current_revision_id, status, visibility,
                    user_id, published_at, created_at, updated_at, deleted_at, version, render_type,
                    password, seo_title, seo_keywords, seo_description, category_id, published_revision_id,
                    view_count, featured, allow_comment, extra_info, visibility_regions,
                    ai_summary_status, content_declarations, repost_policy_code
                ) values (
                    :id, :slug, cast(:title as jsonb), cast(:summary as jsonb), cast(:aiSummary as jsonb),
                    null, :status, :visibility, :userId, :publishedAt, current_timestamp, current_timestamp,
                    null, 0, :renderType, null, :seoTitle, :seoKeywords, :seoDescription, :categoryId,
                    null, :viewCount, :featured, :allowComment, cast(:extraInfo as jsonb),
                    cast(:visibilityRegions as jsonb), :aiSummaryStatus, cast(:contentDeclarations as jsonb),
                    :repostPolicyCode
                )
                """)
                .setParameter("id", post.id)
                .setParameter("slug", post.slug)
                .setParameter("title", writeJsonValue(post.title))
                .setParameter("summary", writeJsonValue(post.summary))
                .setParameter("aiSummary", writeJsonValue(post.aiSummary))
                .setParameter("status", post.status.getCode())
                .setParameter("visibility", post.visibility)
                .setParameter("userId", post.user.id)
                .setParameter("publishedAt", post.publishedAt)
                .setParameter("renderType", post.renderType.code())
                .setParameter("seoTitle", post.seoTitle)
                .setParameter("seoKeywords", post.seoKeywords)
                .setParameter("seoDescription", post.seoDescription)
                .setParameter("categoryId", categoryId)
                .setParameter("viewCount", post.viewCount)
                .setParameter("featured", post.featured)
                .setParameter("allowComment", post.allowComment)
                .setParameter("extraInfo", writeJsonValue(post.extraInfo))
                .setParameter("visibilityRegions", writeJsonValue(post.visibilityRegions))
                .setParameter("aiSummaryStatus", post.aiSummaryStatus)
                .setParameter("contentDeclarations", writeJsonValue(post.contentDeclarations))
                .setParameter("repostPolicyCode", post.repostPolicyCode)
                .executeUpdate();
        synchronizeSequence("posts");
    }

    private void insertPublicRevision(PostRevision revision) throws Exception {
        entityManager.createNativeQuery("""
                insert into post_revisions (
                    id, post_id, title, content_markdown, editor_type, revision_number, created_by, created_at
                ) values (
                    :id, :postId, cast(:title as jsonb), cast(:contentMarkdown as jsonb),
                    :editorType, :revisionNumber, :createdBy, current_timestamp
                )
                """)
                .setParameter("id", revision.id)
                .setParameter("postId", revision.post.id)
                .setParameter("title", writeJsonValue(revision.title))
                .setParameter("contentMarkdown", writeJsonValue(revision.contentMarkdown))
                .setParameter("editorType", revision.editorType)
                .setParameter("revisionNumber", revision.revisionNumber)
                .setParameter("createdBy", revision.createdBy.id)
                .executeUpdate();
        synchronizeSequence("post_revisions");
    }

    private Map<String, Object> readPublicExtraInfo(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("公开同步字段类型无效: " + fieldName);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String allowedField : List.of("points_price", "free_lines", "related_store_items")) {
            JsonNode allowedValue = value.get(allowedField);
            if (allowedValue != null && !allowedValue.isNull()) {
                result.put(allowedField, objectMapper.convertValue(allowedValue, Object.class));
            }
        }
        return result;
    }

    private OffsetDateTime readOptionalDateTime(JsonNode node, String fieldName) {
        String value = readOptionalText(node, fieldName);
        if (value == null) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    private Boolean readOptionalBoolean(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("公开同步字段类型无效: " + fieldName);
        }
        return value.asBoolean();
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
