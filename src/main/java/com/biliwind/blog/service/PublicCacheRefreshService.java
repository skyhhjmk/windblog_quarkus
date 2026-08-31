package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;

@ApplicationScoped
public class PublicCacheRefreshService {

    private static final Logger LOG = Logger.getLogger(PublicCacheRefreshService.class);

    @Inject
    CacheService cacheService;

    @Inject
    PostAccessService postAccessService;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    public void onPostChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) PostSyncedEvent event) {
        refreshPostCaches(event.getPostId());
    }

    public void onDataChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) DataSyncEvent event) {
        String entityType = event.getEntityType();
        if ("CATEGORY".equals(entityType)) {
            refreshCategoryCaches();
        } else if ("TAG".equals(entityType)) {
            refreshTagCaches();
        } else if ("MEDIA".equals(entityType)) {
            refreshMediaCache(event.getEntityId(), event.getAction());
        } else if ("LINK".equals(entityType)) {
            refreshListCaches();
        }
    }

    public void refreshPostCaches(Long postId) {
        try {
            Post post = Post.findById(postId);
            if (isPublicReadModelCandidate(post)) {
                cacheService.set(CacheService.Keys.postMeta(post.slug), toPublicPostSnapshot(post), Duration.ofMinutes(30));
            } else if (post != null && post.slug != null) {
                cacheService.delete(CacheService.Keys.postMeta(post.slug));
            }
            refreshListCaches();
        } catch (Exception e) {
            LOG.warnf("刷新文章缓存失败: postId=%d, error=%s", postId, e.getMessage());
        }
    }

    /**
     * Reads the public article read model and lazily rebuilds it after a cache miss.
     * The fallback still uses the source entity, but only to rebuild the explicit
     * whitelist snapshot; callers never receive the entity as their public view.
     */
    public Optional<PublicPostSnapshot> findPublishedSnapshot(String slug, String regionCode) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }

        Optional<PublicPostSnapshot> cached = cacheService.get(
                CacheService.Keys.postMeta(slug), PublicPostSnapshot.class);
        if (cached.isPresent()) {
            PublicPostSnapshot snapshot = cached.get();
            if (isVisibleInRegion(snapshot.visibilityRegions(), regionCode)) {
                return Optional.of(snapshot);
            }
            return Optional.empty();
        }

        Post post = Post.find(
                "slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                slug, com.biliwind.blog.model.PostStatus.PUBLISHED).firstResult();
        if (!isPublicReadModelCandidate(post)
                || !isVisibleInRegion(post.visibilityRegions, regionCode)) {
            return Optional.empty();
        }

        PublicPostSnapshot snapshot = toPublicPostSnapshot(post);
        cacheService.set(CacheService.Keys.postMeta(slug), snapshot, Duration.ofMinutes(30));
        return Optional.of(snapshot);
    }

    /**
     * Reads the bounded homepage list model. A cache miss is allowed to rebuild
     * the whitelist projection from PostgreSQL, but the caller never receives a
     * Post entity and the query is always page-bounded.
     */
    public PublicIndexPageSnapshot findPublishedIndexPage(int page, int pageSize,
                                                           String language, String regionCode) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(50, pageSize));
        String cacheKey = CacheService.Keys.indexPage(safePage,
                safeLanguage(language) + ":" + safeRegion(regionCode) + ":size:" + safePageSize);
        Optional<PublicIndexPageSnapshot> cached = cacheService.get(cacheKey, PublicIndexPageSnapshot.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        String regionPattern = "%\"" + safeRegion(regionCode) + "\"%";
        Map<String, Object> parameters = Map.of(
                "status", PostStatus.PUBLISHED,
                "regionPattern", regionPattern);
        PanacheQuery<Post> query = Post.find(
                "status = :status and deletedAt is null and visibility = 0 "
                        + "and publishedRevision is not null "
                        + "and (visibilityRegions is null or cast(visibilityRegions as String) like :regionPattern) "
                        + "order by publishedAt desc nulls last, createdAt desc, id desc",
                parameters);
        long totalPostsCount = query.count();
        List<Post> posts = query.page(Page.of(safePage - 1, safePageSize)).list();
        List<PublicPostListSnapshot> postItems = toPublicPostListSnapshots(posts);
        long totalPages = totalPostsCount == 0
                ? 1
                : (long) Math.ceil((double) totalPostsCount / safePageSize);
        PublicIndexPageSnapshot result = new PublicIndexPageSnapshot(
                postItems, totalPostsCount, totalPages, safeLanguage(language), safeRegion(regionCode));
        cacheService.set(cacheKey, result, Duration.ofMinutes(30));
        return result;
    }

    /**
     * Reads the bounded public feed projection. The source query is only used
     * to rebuild a whitelisted snapshot after Redis misses.
     */
    public List<PublicPostListSnapshot> findPublishedFeed(int limit,
                                                           String language, String regionCode) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        String safeLang = safeLanguage(language);
        String safeRegionCode = safeRegion(regionCode);
        String cacheKey = CacheService.Keys.publicFeed(safeLang, safeRegionCode, safeLimit);
        Optional<List<PublicPostListSnapshot>> cached = cacheService.get(
                cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<PublicPostListSnapshot>>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        String regionPattern = "%\"" + safeRegionCode + "\"%";
        Map<String, Object> parameters = Map.of(
                "status", PostStatus.PUBLISHED,
                "regionPattern", regionPattern);
        PanacheQuery<Post> query = Post.find(
                "status = :status and deletedAt is null and visibility = 0 "
                        + "and publishedRevision is not null "
                        + "and (visibilityRegions is null or cast(visibilityRegions as String) like :regionPattern) "
                        + "order by publishedAt desc nulls last, createdAt desc, id desc",
                parameters);
        List<PublicPostListSnapshot> result = toPublicPostListSnapshots(
                query.page(Page.ofSize(safeLimit)).list());
        cacheService.set(cacheKey, result, Duration.ofMinutes(30));
        return result;
    }

    public void refreshCategoryCaches() {
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_CATEGORIES + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
        invalidatePublicPostReadModels();
        refreshListCaches();
    }

    public void refreshTagCaches() {
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_TAGS + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
        invalidatePublicPostReadModels();
        refreshListCaches();
    }

    private void invalidatePublicPostReadModels() {
        cacheService.deletePattern(CacheService.Keys.POST_META_PREFIX + "*");
    }

    public void refreshMediaCache(Long mediaId, String action) {
        try {
            Media media = Media.findById(mediaId);
            if (media == null) {
                return;
            }
            if (media.storageKey == null) {
                return;
            }
            boolean protectedMedia = postAccessService.hasProtectedMediaReference(media.id);
            if ("DELETE".equals(action) || media.deletedAt != null || protectedMedia) {
                cacheService.delete(CacheService.Keys.mediaMeta(media.storageKey));
            } else {
                cacheService.set(CacheService.Keys.mediaMeta(media.storageKey), toPublicMediaSnapshot(media), Duration.ofHours(1));
            }
            invalidatePostsReferencingMedia(media.id);
        } catch (Exception e) {
            LOG.warnf("刷新媒体缓存失败: mediaId=%d, error=%s", mediaId, e.getMessage());
        }
    }

    private void invalidatePostsReferencingMedia(Long mediaId) {
        if (mediaId == null) {
            return;
        }
        List<PostMedia> references = PostMedia.list("media.id = ?1", mediaId);
        for (PostMedia reference : references) {
            if (reference.post != null && reference.post.slug != null) {
                cacheService.delete(CacheService.Keys.postMeta(reference.post.slug));
            }
        }
    }

    private boolean isPublicReadModelCandidate(Post post) {
        if (post == null || post.slug == null || post.deletedAt != null
                || post.status != com.biliwind.blog.model.PostStatus.PUBLISHED
                || post.publishedRevision == null) {
            return false;
        }
        return post.visibility == 0 || post.visibility == 2;
    }

    static boolean requiresProtectedPreview(Post post, long postPrice) {
        return postPrice > 0 || (post != null && post.visibility == 2);
    }

    private boolean isVisibleInRegion(List<String> visibilityRegions, String regionCode) {
        if (visibilityRegions == null || visibilityRegions.isEmpty()) {
            return true;
        }
        String safeRegion = regionCode;
        if (safeRegion == null || safeRegion.isBlank()) {
            safeRegion = "GLOBAL";
        }
        for (String region : visibilityRegions) {
            if (region != null && ("GLOBAL".equalsIgnoreCase(region.trim())
                    || safeRegion.equalsIgnoreCase(region.trim()))) {
                return true;
            }
        }
        return false;
    }

    private PublicPostSnapshot toPublicPostSnapshot(Post post) {
        Map<String, String> previewByLanguage = new LinkedHashMap<>();
        if (post.publishedRevision != null && post.publishedRevision.contentMarkdown != null) {
            long postPrice = postAccessService.getPostPrice(post);
            boolean contentRequiresUnlock = requiresProtectedPreview(post, postPrice);
            int freeLines = postAccessService.getFreeLines(post);
            for (Map.Entry<String, String> entry : post.publishedRevision.contentMarkdown.entrySet()) {
                previewByLanguage.put(entry.getKey(), postAccessService.getPreviewOnlyContent(
                        entry.getValue(), freeLines, contentRequiresUnlock, post.id, postPrice, null));
            }
        }
        String categorySlug = post.category == null ? null : post.category.slug;
        Map<String, String> categoryName = post.category == null
                ? Map.of() : copyMap(post.category.name);
        List<PublicTagSnapshot> tags = new ArrayList<>();
        List<PostTag> postTags = PostTag.list("post.id = ?1", post.id);
        for (PostTag postTag : postTags) {
            if (postTag.tag != null && postTag.tag.slug != null) {
                tags.add(new PublicTagSnapshot(copyMap(postTag.tag.name), postTag.tag.slug));
            }
        }
        List<PublicAttachmentSnapshot> attachments = new ArrayList<>();
        List<PostMedia> postMedia = PostMedia.list("post.id = ?1 and usageType = 3", post.id);
        for (PostMedia postMedium : postMedia) {
            if (postMedium.media == null || postMedium.media.deletedAt != null) {
                continue;
            }
            attachments.add(new PublicAttachmentSnapshot(
                    postMedium.media.fileName,
                    postMedium.media.size,
                    copyList(postMedium.media.visibilityRegions),
                    copyList(postMedium.media.hiddenRegions)));
        }
        return new PublicPostSnapshot(
                post.id,
                post.slug,
                post.publishedRevision == null ? null : post.publishedRevision.id,
                copyMap(post.title),
                copyMap(post.summary),
                copyMap(post.aiSummary),
                post.aiSummaryStatus,
                previewByLanguage,
                post.renderType,
                post.seoTitle,
                post.seoKeywords,
                post.seoDescription,
                categoryName,
                categorySlug,
                post.user == null ? "Unknown" : post.user.username,
                post.publishedAt,
                post.updatedAt,
                post.viewCount,
                post.featured,
                post.allowComment,
                post.visibility,
                postAccessService.getPostPrice(post),
                tags,
                attachments,
                copyList(post.visibilityRegions),
                copyList(post.contentDeclarations),
                repostPolicyCatalog.resolve(post).code(),
                resolveRelatedStoreItems(post.extraInfo));
    }

    private List<PublicPostListSnapshot> toPublicPostListSnapshots(List<Post> posts) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = new ArrayList<>();
        for (Post post : posts) {
            postIds.add(post.id);
        }
        Map<Long, List<PublicTagSnapshot>> tagsByPost = new LinkedHashMap<>();
        List<PostTag> postTags = PostTag.list("post.id in ?1", postIds);
        for (PostTag postTag : postTags) {
            if (postTag.post == null || postTag.tag == null || postTag.tag.slug == null) {
                continue;
            }
            tagsByPost.computeIfAbsent(postTag.post.id, ignored -> new ArrayList<>())
                    .add(new PublicTagSnapshot(copyMap(postTag.tag.name), postTag.tag.slug));
        }

        List<PublicPostListSnapshot> result = new ArrayList<>();
        for (Post post : posts) {
            Map<String, String> categoryName = post.category == null
                    ? Map.of() : copyMap(post.category.name);
            String categorySlug = post.category == null ? null : post.category.slug;
            result.add(new PublicPostListSnapshot(
                    post.slug,
                    copyMap(post.title),
                    copyMap(post.summary),
                    copyMap(post.aiSummary),
                    post.aiSummaryStatus,
                    post.publishedAt,
                    post.updatedAt,
                    post.createdAt,
                    categoryName,
                    categorySlug,
                    post.user == null ? "Unknown" : post.user.username,
                    tagsByPost.getOrDefault(post.id, List.of()),
                    copyList(post.visibilityRegions)));
        }
        return result;
    }

    private List<PublicStoreItemSnapshot> resolveRelatedStoreItems(Object extraInfo) {
        if (extraInfo == null) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.convertValue(
                    extraInfo, com.fasterxml.jackson.databind.JsonNode.class);
            com.fasterxml.jackson.databind.JsonNode relatedStoreItemNodes = node.get("related_store_items");
            if (relatedStoreItemNodes == null || !relatedStoreItemNodes.isArray()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode id : relatedStoreItemNodes) {
                if (id.canConvertToLong()) {
                    ids.add(id.longValue());
                }
            }
            if (ids.isEmpty()) {
                return List.of();
            }
            List<StoreItem> storeItems = StoreItem.list("id in ?1 and status = 1", ids);
            Map<Long, StoreItem> storeItemsById = new LinkedHashMap<>();
            for (StoreItem storeItem : storeItems) {
                storeItemsById.put(storeItem.id, storeItem);
            }
            List<PublicStoreItemSnapshot> result = new ArrayList<>();
            for (Long id : ids) {
                StoreItem storeItem = storeItemsById.get(id);
                if (storeItem != null) {
                    result.add(new PublicStoreItemSnapshot(
                            storeItem.name, storeItem.description, storeItem.price));
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private PublicMediaSnapshot toPublicMediaSnapshot(Media media) {
        Map<String, String> variants = new LinkedHashMap<>();
        if (media.metadata != null) {
            copyString(media.metadata, variants, "placeholderUrl");
            copyString(media.metadata, variants, "thumbnailUrl");
            copyString(media.metadata, variants, "previewUrl");
            copyString(media.metadata, variants, "webpUrl");
            copyString(media.metadata, variants, "coverUrl");
        }
        return new PublicMediaSnapshot(
                media.storageKey,
                media.fileName,
                media.mimeType,
                media.size,
                media.mediaType,
                media.width,
                media.height,
                variants,
                copyList(media.visibilityRegions),
                copyList(media.hiddenRegions));
    }

    private void copyString(Map<String, Object> source, Map<String, String> target, String key) {
        Object value = source.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            target.put(key, stringValue);
        }
    }

    private Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(source);
    }

    private List<String> copyList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(source);
    }

    public record PublicPostSnapshot(
            Long postId,
            String slug,
            Long publishedRevisionId,
            Map<String, String> title,
            Map<String, String> summary,
            Map<String, String> aiSummary,
            Short aiSummaryStatus,
            Map<String, String> previewContent,
            com.biliwind.blog.model.PostRenderType renderType,
            String seoTitle,
            String seoKeywords,
            String seoDescription,
            Map<String, String> categoryName,
            String categorySlug,
            String authorName,
            OffsetDateTime publishedAt,
            OffsetDateTime updatedAt,
            Long viewCount,
            Boolean featured,
            Boolean allowComment,
            short visibility,
            long postPrice,
            List<PublicTagSnapshot> tags,
            List<PublicAttachmentSnapshot> attachments,
            List<String> visibilityRegions,
            List<String> contentDeclarations,
            String repostPolicyCode,
            List<PublicStoreItemSnapshot> relatedStoreItems) {
    }

    public record PublicStoreItemSnapshot(
            String name,
            String description,
            Long price) {
    }

    public record PublicIndexPageSnapshot(
            List<PublicPostListSnapshot> posts,
            long totalPostsCount,
            long totalPages,
            String language,
            String regionCode) {
    }

    public record PublicPostListSnapshot(
            String slug,
            Map<String, String> title,
            Map<String, String> summary,
            Map<String, String> aiSummary,
            Short aiSummaryStatus,
            OffsetDateTime publishedAt,
            OffsetDateTime updatedAt,
            OffsetDateTime createdAt,
            Map<String, String> categoryName,
            String categorySlug,
            String authorName,
            List<PublicTagSnapshot> tags,
            List<String> visibilityRegions) {
    }

    public record PublicTagSnapshot(
            Map<String, String> name,
            String slug) {
    }

    public record PublicAttachmentSnapshot(
            String fileName,
            Long size,
            List<String> visibilityRegions,
            List<String> hiddenRegions) {
    }

    public record PublicMediaSnapshot(
            String storageKey,
            String fileName,
            String mimeType,
            Long size,
            short mediaType,
            Integer width,
            Integer height,
            Map<String, String> variants,
            List<String> visibilityRegions,
            List<String> hiddenRegions) {
    }

    private void refreshListCaches() {
        cacheService.deletePattern(CacheService.Keys.INDEX_PAGE_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.PUBLIC_FEED_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_RECENT_POSTS + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }

    private String safeLanguage(String language) {
        return language == null || language.isBlank() ? "zh" : language;
    }

    private String safeRegion(String regionCode) {
        return regionCode == null || regionCode.isBlank() ? "GLOBAL" : regionCode;
    }
}
