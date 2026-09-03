package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.controller.api.admin.dto.AdminMediaDtos;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.MediaManagementService;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Allowlisted category, tag, and image operations for the signed Codex boundary. */
@ApplicationScoped
public class CodexCreatorContentService {
    public static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "category.list", "category.read", "category.create", "category.update",
            "tag.list", "tag.read", "tag.create", "tag.update", "media.upload");

    private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");
    private static final int MAX_LOCALIZED_VALUES = 20;
    private static final int MAX_LOCALE_LENGTH = 32;
    private static final int MAX_NAME_LENGTH = 500;
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;

    @Inject
    ObjectMapper mapper;

    @Inject
    EntityManager entityManager;

    @Inject
    AuditService auditService;

    @Inject
    CacheService cacheService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Inject
    MediaManagementService mediaService;

    @ConfigProperty(name = "windblog.ai.codex-creator.max-image-bytes", defaultValue = "8388608")
    long maxImageBytes;

    @Transactional
    public Map<String, Object> execute(String operation, JsonNode input, String traceId) {
        if (!ALLOWED_OPERATIONS.contains(operation)) {
            throw new BadRequestException("Codex 内容操作未被允许");
        }
        JsonNode payload = input == null || input.isNull() ? mapper.createObjectNode() : input;
        return switch (operation) {
            case "category.list", "category.read" -> listCategories();
            case "category.create" -> createCategory(payload, traceId);
            case "category.update" -> updateCategory(payload, traceId);
            case "tag.list", "tag.read" -> listTags();
            case "tag.create" -> createTag(payload, traceId);
            case "tag.update" -> updateTag(payload, traceId);
            case "media.upload" -> uploadImage(payload, traceId);
            default -> throw new BadRequestException("Codex 内容操作未被允许");
        };
    }

    private Map<String, Object> listCategories() {
        List<Map<String, Object>> items = Category.<Category>listAll(Sort.by("path"))
                .stream().map(this::categoryView).toList();
        return pageView(items, items.size());
    }

    private Map<String, Object> createCategory(JsonNode payload, String traceId) {
        Map<String, String> name = localized(payload.get("name"), "name", true,
                MAX_NAME_LENGTH, "zh-CN");
        Map<String, String> description = localized(payload.get("description"), "description", false,
                MAX_DESCRIPTION_LENGTH, "zh-CN");
        String slug = normalizedSlug(text(payload, "slug", null), name);
        Long parentId = optionalLong(payload, "parentId");
        Category parent = parentId == null ? null : requireCategory(parentId);
        ensureUniqueCategorySlug(slug, null);

        Category category = new Category();
        category.parent = parent;
        category.slug = slug;
        category.name = name;
        category.description = description;
        category.createdAt = OffsetDateTime.now();
        category.postCount = 0L;
        category.persist();
        entityManager.flush();
        entityManager.refresh(category);
        recordCategoryChange("create", category, traceId);
        return categoryView(category);
    }

    private Map<String, Object> updateCategory(JsonNode payload, String traceId) {
        long id = requiredLong(payload, "id");
        Category category = requireCategory(id);
        Map<String, String> name = payload.has("name")
                ? localized(payload.get("name"), "name", true, MAX_NAME_LENGTH, "zh-CN")
                : category.name;
        Map<String, String> description = payload.has("description")
                ? localized(payload.get("description"), "description", false, MAX_DESCRIPTION_LENGTH, "zh-CN")
                : category.description;
        String slug = normalizedSlug(text(payload, "slug", category.slug), name);
        Long parentId = payload.has("parentId") ? optionalLong(payload, "parentId")
                : category.parent == null ? null : category.parent.id;
        Category parent = parentId == null ? null : requireCategory(parentId);
        ensureNoCategoryCycle(category, parent);
        ensureUniqueCategorySlug(slug, id);

        category.parent = parent;
        category.slug = slug;
        category.name = name;
        category.description = description;
        category.persist();
        entityManager.flush();
        entityManager.refresh(category);
        recordCategoryChange("update", category, traceId);
        return categoryView(category);
    }

    private Map<String, Object> listTags() {
        List<Map<String, Object>> items = Tag.<Tag>listAll(Sort.by("createdAt").descending())
                .stream().map(this::tagView).toList();
        return pageView(items, items.size());
    }

    private Map<String, Object> createTag(JsonNode payload, String traceId) {
        Map<String, String> name = localized(payload.get("name"), "name", true,
                MAX_NAME_LENGTH, "zh-CN");
        Map<String, String> description = localized(payload.get("description"), "description", false,
                MAX_DESCRIPTION_LENGTH, "zh-CN");
        String slug = normalizedSlug(text(payload, "slug", null), name);
        ensureUniqueTagSlug(slug, null);

        Tag tag = new Tag();
        tag.slug = slug;
        tag.name = name;
        tag.description = description;
        tag.createdAt = OffsetDateTime.now();
        tag.persist();
        entityManager.flush();
        entityManager.refresh(tag);
        recordTagChange("create", tag, traceId);
        return tagView(tag);
    }

    private Map<String, Object> updateTag(JsonNode payload, String traceId) {
        long id = requiredLong(payload, "id");
        Tag tag = Tag.findById(id);
        if (tag == null) {
            throw new NotFoundException("标签不存在");
        }
        Map<String, String> name = payload.has("name")
                ? localized(payload.get("name"), "name", true, MAX_NAME_LENGTH, "zh-CN")
                : tag.name;
        Map<String, String> description = payload.has("description")
                ? localized(payload.get("description"), "description", false, MAX_DESCRIPTION_LENGTH, "zh-CN")
                : tag.description;
        String slug = normalizedSlug(text(payload, "slug", tag.slug), name);
        ensureUniqueTagSlug(slug, id);

        tag.slug = slug;
        tag.name = name;
        tag.description = description;
        tag.persist();
        entityManager.flush();
        entityManager.refresh(tag);
        recordTagChange("update", tag, traceId);
        return tagView(tag);
    }

    private Map<String, Object> uploadImage(JsonNode payload, String traceId) {
        String fileName = requiredText(payload, "fileName", 255);
        String mimeType = requiredText(payload, "mimeType", 100).toLowerCase(Locale.ROOT);
        String encoded = requiredText(payload, "dataBase64", maxEncodedLength());
        DecodedImage image = decodeImage(encoded, mimeType);
        if (image.bytes().length > maxImageBytes) {
            throw new BadRequestException("图片大小超出 Codex 上传限制");
        }

        Media media = mediaService.storeCodexUploadedImage(
                new ByteArrayInputStream(image.bytes()), fileName, image.mimeType(), image.bytes().length,
                maxImageBytes, Map.of("aiUploaded", true, "uploadSource", "CODEX_CREATOR",
                        "generationMethod", "CODEX_APP_SERVER"));
        dataSyncEvent.fire(new DataSyncEvent("MEDIA", media.id, "UPSERT"));
        auditService.log("media", media.id, "create", null,
                Map.of("source", "CODEX_CREATOR", "operation", "media.upload",
                        "generationMethod", "CODEX_APP_SERVER"), null);
        return mediaView(media);
    }

    private void recordCategoryChange(String action, Category category, String traceId) {
        auditService.log("category", category.id, action, null,
                Map.of("source", "CODEX_CREATOR", "operation", "category." + action,
                        "traceId", traceId == null ? "" : traceId), null);
        dataSyncEvent.fire(new DataSyncEvent("CATEGORY", category.id, "UPSERT"));
        cacheService.delete(CacheService.Keys.ALL_CATEGORIES);
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_CATEGORIES + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }

    private void recordTagChange(String action, Tag tag, String traceId) {
        auditService.log("tag", tag.id, action, null,
                Map.of("source", "CODEX_CREATOR", "operation", "tag." + action,
                        "traceId", traceId == null ? "" : traceId), null);
        dataSyncEvent.fire(new DataSyncEvent("TAG", tag.id, "UPSERT"));
        cacheService.delete(CacheService.Keys.ALL_TAGS);
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_TAGS + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }

    private Category requireCategory(long id) {
        Category category = Category.findById(id);
        if (category == null) {
            throw new BadRequestException("父分类不存在");
        }
        return category;
    }

    private void ensureUniqueCategorySlug(String slug, Long id) {
        long count = id == null
                ? Category.count("slug", slug)
                : Category.count("slug = ?1 and id <> ?2", slug, id);
        if (count > 0) {
            throw new BadRequestException("分类 slug 已存在");
        }
    }

    private void ensureUniqueTagSlug(String slug, Long id) {
        long count = id == null
                ? Tag.count("slug", slug)
                : Tag.count("slug = ?1 and id <> ?2", slug, id);
        if (count > 0) {
            throw new BadRequestException("标签 slug 已存在");
        }
    }

    private void ensureNoCategoryCycle(Category category, Category parent) {
        Category cursor = parent;
        int depth = 0;
        while (cursor != null && depth++ < 1_000) {
            if (category.id.equals(cursor.id)) {
                throw new BadRequestException("分类不能成为自己的后代父分类");
            }
            cursor = cursor.parent;
        }
        if (cursor != null) {
            throw new BadRequestException("分类层级过深");
        }
    }

    private Map<String, Object> categoryView(Category category) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", category.id);
        view.put("parentId", category.parent == null ? null : category.parent.id);
        view.put("slug", category.slug);
        view.put("name", category.name);
        view.put("description", category.description);
        view.put("path", category.path);
        view.put("createdAt", category.createdAt);
        view.put("postCount", category.postCount == null ? 0L : category.postCount);
        return view;
    }

    private Map<String, Object> tagView(Tag tag) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", tag.id);
        view.put("slug", tag.slug);
        view.put("name", tag.name);
        view.put("description", tag.description);
        view.put("createdAt", tag.createdAt);
        return view;
    }

    private Map<String, Object> mediaView(Media media) {
        AdminMediaDtos.MediaItem item = mediaService.toDto(media, List.of());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.id());
        view.put("url", item.url());
        view.put("fileName", item.fileName());
        view.put("mimeType", item.mimeType());
        view.put("size", item.size());
        view.put("mediaType", item.mediaType());
        view.put("width", item.width());
        view.put("height", item.height());
        view.put("metadata", item.metadata());
        view.put("processingStatus", item.processingStatus());
        view.put("processingProgress", item.processingProgress());
        view.put("virusScanStatus", item.virusScanStatus());
        view.put("createdAt", item.createdAt());
        return view;
    }

    private static Map<String, Object> pageView(List<Map<String, Object>> items, long total) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("items", items);
        view.put("total", total);
        view.put("page", 1);
        view.put("pageSize", Math.max(1, items.size()));
        return view;
    }

    private DecodedImage decodeImage(String encoded, String mimeType) {
        String value = encoded.trim();
        String effectiveMime = mimeType;
        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new BadRequestException("图片 data URI 格式无效");
            }
            String header = value.substring(5, comma);
            if (!header.toLowerCase(Locale.ROOT).endsWith(";base64")) {
                throw new BadRequestException("图片必须使用 base64 数据");
            }
            int semicolon = header.indexOf(';');
            String dataMime = semicolon <= 0 ? "" : header.substring(0, semicolon).trim().toLowerCase(Locale.ROOT);
            if (!IMAGE_MIME_TYPES.contains(dataMime) || !dataMime.equals(effectiveMime)) {
                throw new BadRequestException("图片 MIME 类型与 data URI 不一致");
            }
            value = value.substring(comma + 1);
        }
        if (!IMAGE_MIME_TYPES.contains(effectiveMime)) {
            throw new BadRequestException("Codex 只允许上传 PNG、JPEG、GIF 或 WebP 图片");
        }
        String compact = value.replaceAll("\\s+", "");
        if (compact.isBlank() || compact.length() > maxEncodedLength()) {
            throw new BadRequestException("图片数据为空或超出大小限制");
        }
        try {
            return new DecodedImage(Base64.getDecoder().decode(compact), effectiveMime);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("图片 base64 数据无效");
        }
    }

    private int maxEncodedLength() {
        long max = Math.max(1L, maxImageBytes);
        long encoded = ((max + 2L) / 3L) * 4L + 512L;
        return (int) Math.min(Integer.MAX_VALUE, encoded);
    }

    private static Map<String, String> localized(JsonNode node, String field, boolean required,
                                                 int maxValueLength, String defaultLocale) {
        if (node == null || node.isNull()) {
            if (required) throw new BadRequestException(field + " 不能为空");
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (value.isBlank()) throw new BadRequestException(field + " 不能为空");
            if (value.length() > maxValueLength) throw new BadRequestException(field + " 内容过长");
            result.put(defaultLocale, value);
            return result;
        }
        if (!node.isObject() || node.size() > MAX_LOCALIZED_VALUES) {
            throw new BadRequestException(field + " 必须是多语言对象");
        }
        node.fields().forEachRemaining(entry -> {
            String locale = entry.getKey().trim();
            JsonNode valueNode = entry.getValue();
            if (locale.isBlank() || locale.length() > MAX_LOCALE_LENGTH
                    || !locale.matches("[A-Za-z0-9_-]+")) {
                throw new BadRequestException(field + " 的语言键无效");
            }
            if (!valueNode.isTextual()) {
                throw new BadRequestException(field + " 的语言值必须是文本");
            }
            String value = valueNode.asText().trim();
            if (!value.isBlank()) {
                if (value.length() > maxValueLength) throw new BadRequestException(field + " 内容过长");
                result.put(locale, value);
            }
        });
        if (required && result.isEmpty()) throw new BadRequestException(field + " 不能为空");
        return result.isEmpty() ? null : result;
    }

    private static String normalizedSlug(String requested, Map<String, String> name) {
        String slug = requested == null || requested.isBlank()
                ? com.biliwind.blog.common.helper.SlugHelper.slugify(name)
                : requested.trim().toLowerCase(Locale.ROOT);
        if (slug.isBlank() || slug.length() > 160 || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new BadRequestException("slug 必须是 1-160 位小写字母、数字或连字符");
        }
        return slug;
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new BadRequestException(field + " 必须是文本");
        return value.asText().trim();
    }

    private static String requiredText(JsonNode payload, String field, int maxLength) {
        String value = text(payload, field, null);
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new BadRequestException(field + " 不能为空且长度不能超过 " + maxLength);
        }
        return value;
    }

    private static long requiredLong(JsonNode payload, String field) {
        Long value = optionalLong(payload, field);
        if (value == null || value <= 0) throw new BadRequestException(field + " 必须是正整数");
        return value;
    }

    private static Long optionalLong(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.canConvertToLong() || value.asLong() <= 0) {
            throw new BadRequestException(field + " 必须是正整数");
        }
        return value.asLong();
    }

    private record DecodedImage(byte[] bytes, String mimeType) {
    }
}
