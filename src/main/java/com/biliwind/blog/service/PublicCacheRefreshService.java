package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostCoverImageHelper;
import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.StoreItem;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.link.LinkMonitorPolicy;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityNotFoundException;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;

@ApplicationScoped
public class PublicCacheRefreshService {

    private static final Logger LOG = Logger.getLogger(PublicCacheRefreshService.class);
    private static final String WESP_PUBLIC_AUTHOR_NAME_KEY = "wesp_public_author_name";
    private static final int PAGE_SIZE = 12;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Duration CATEGORY_TAG_TTL = Duration.ofMinutes(30);

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
            cacheService.deletePattern(CacheService.Keys.PUBLIC_LINKS_PREFIX + "*");
            refreshListCaches();
        }
    }

    /** Pre-loads the category/tag list pages so the first visitors see a warm cache. */
    void onStartup(@Observes StartupEvent ignored) {
        try {
            QuarkusTransaction.requiringNew().run(this::warmPublicCategoryTagCaches);
        } catch (Exception e) {
            LOG.warnf("分类/标签缓存预热失败: %s", e.getMessage());
        }
    }

    public void warmPublicCategoryTagCaches() {
        if (!cacheService.isAvailable()) {
            return;
        }
        for (String language : LanguageConstant.SUPPORTED_LANG_ORDER) {
            try {
                findCategoryListPage(1, language);
                findTagListPage(1, language);
            } catch (Exception e) {
                LOG.warnf("预热分类/标签列表缓存失败: language=%s, error=%s", language, e.getMessage());
            }
        }
    }

    @Transactional
    public void refreshPostCaches(Long postId) {
        try {
            Post post = Post.findById(postId);
            if (isPublicReadModelCandidate(post)) {
                cacheService.set(CacheService.Keys.postMeta(post.slug), toPublicPostSnapshot(post), Duration.ofMinutes(30));
            } else if (post != null && post.slug != null) {
                cacheService.delete(CacheService.Keys.postMeta(post.slug));
            }
            refreshListCaches();
            // Fuzzy article-map relations are derived from indexed content.
            cacheService.deletePattern(CacheService.Keys.ARTICLE_MAP_RELATED_PREFIX + "*");
        } catch (Exception e) {
            LOG.warnf("刷新文章缓存失败: postId=%d, error=%s", postId, e.getMessage());
        }
    }

    /**
     * Reads the public article read model and lazily rebuilds it after a cache miss.
     * The fallback still uses the source entity, but only to rebuild the explicit
     * whitelist snapshot; callers never receive the entity as their public view.
     */
    @Transactional
    public Optional<PublicPostSnapshot> findPublishedSnapshot(String slug, String regionCode) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }

        Optional<PublicPostSnapshot> cached = cacheService.get(
                CacheService.Keys.postMeta(slug), PublicPostSnapshot.class);
        if (cached.isPresent()) {
            PublicPostSnapshot snapshot = cached.get();
            if (RegionVisibility.isVisible(snapshot.visibilityRegions(), BlogRegion.fromCode(regionCode))) {
                return Optional.of(snapshot);
            }
            return Optional.empty();
        }

        Post post = Post.find(
                "slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                slug, com.biliwind.blog.model.PostStatus.PUBLISHED).firstResult();
        if (!isPublicReadModelCandidate(post)
                || !RegionVisibility.isVisible(post.visibilityRegions, BlogRegion.fromCode(regionCode))) {
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
    @Transactional
    public PublicIndexPageSnapshot findPublishedIndexPage(int page, int pageSize,
                                                           String language, String regionCode) {
        return findPublishedIndexPage(page, pageSize, language, regionCode, null);
    }

    /**
     * Reads a paged projection of published posts for the homepage, optionally filtered by article mode.
     */
    @Transactional
    public PublicIndexPageSnapshot findPublishedIndexPage(int page, int pageSize,
                                                           String language, String regionCode,
                                                           com.biliwind.blog.model.ArticleMode articleMode) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(50, pageSize));
        String modeKey = articleMode == null ? "all" : articleMode.value();
        // The cover-image projection changed; a versioned suffix avoids serving
        // pre-upgrade snapshots (which deserialize with a null cover) for 30 minutes.
        String cacheKey = CacheService.Keys.indexPage(safePage,
                safeLanguage(language) + ":" + safeRegion(regionCode) + ":size:" + safePageSize + ":mode:" + modeKey + ":cover-v1");
        Optional<PublicIndexPageSnapshot> cached = cacheService.get(cacheKey, PublicIndexPageSnapshot.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        RegionVisibility.NamedFilter regionFilter =
                RegionVisibility.jpqlFilter(BlogRegion.fromCode(safeRegion(regionCode)));
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("status", PostStatus.PUBLISHED);
        parameters.putAll(regionFilter.parameters());

        StringBuilder queryBuilder = new StringBuilder(
                "status = :status and deletedAt is null and visibility = 0 "
                        + "and publishedRevision is not null "
                        + "and " + regionFilter.fragment() + " ");
        if (articleMode != null) {
            queryBuilder.append("and articleMode = :articleMode ");
            parameters.put("articleMode", articleMode);
        }
        queryBuilder.append("order by publishedAt desc nulls last, createdAt desc, id desc");

        PanacheQuery<Post> query = Post.find(queryBuilder.toString(), parameters);
        long totalPostsCount = query.count();
        List<Post> posts = query.page(Page.of(safePage - 1, safePageSize)).list();
        List<PublicPostListSnapshot> postItems = toPublicPostListSnapshots(posts, language);
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

        RegionVisibility.NamedFilter regionFilter =
                RegionVisibility.jpqlFilter(BlogRegion.fromCode(safeRegionCode));
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("status", PostStatus.PUBLISHED);
        parameters.putAll(regionFilter.parameters());
        PanacheQuery<Post> query = Post.find(
                "status = :status and deletedAt is null and visibility = 0 "
                        + "and publishedRevision is not null "
                        + "and " + regionFilter.fragment() + " "
                        + "order by publishedAt desc nulls last, createdAt desc, id desc",
                parameters);
        List<PublicPostListSnapshot> result = toPublicPostListSnapshots(
                query.page(Page.ofSize(safeLimit)).list(), safeLang);
        cacheService.set(cacheKey, result, Duration.ofMinutes(30));
        return result;
    }

    /**
     * Cached category list page. Free-text searches are loaded directly because
     * a keyword would make the key space unbounded.
     */
    @Transactional
    public PublicCategoryListPage findCategoryListPage(int page, String language) {
        String lang = safeLanguage(language);
        int requestedPage = Math.max(1, page);
        return cacheService.getOrLoad(CacheService.Keys.categoryListPage(requestedPage, lang),
                        PublicCategoryListPage.class, CATEGORY_TAG_TTL,
                        () -> loadCategoryListPage(requestedPage, lang, ""))
                .orElseGet(() -> loadCategoryListPage(requestedPage, lang, ""));
    }

    @Transactional
    public PublicCategoryListPage loadCategoryListPage(int page, String language, String keyword) {
        String lang = safeLanguage(language);
        String safeKeyword = keyword == null ? "" : keyword.trim();
        PanacheQuery<Category> query;
        if (safeKeyword.isBlank()) {
            query = Category.find("enabled = true order by path");
        } else {
            String pattern = "%" + safeKeyword.toLowerCase(Locale.ROOT) + "%";
            query = Category.find("enabled = true and (lower(cast(name as String)) like ?1 "
                    + "or lower(cast(description as String)) like ?1 or lower(slug) like ?1 "
                    + "or lower(path) like ?1) order by path", pattern);
        }
        long totalCount = query.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        int currentPage = Math.min(Math.max(1, page), totalPages);
        List<Category> categories = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        Map<Long, Long> childCounts = loadEnabledChildCounts(categories);
        List<PublicCategoryListItem> items = categories.stream()
                .map(category -> toCategoryListItem(category, lang,
                        childCounts.getOrDefault(category.id, 0L)))
                .toList();
        return new PublicCategoryListPage(currentPage, totalPages, totalCount, items);
    }

    @Transactional
    public PublicTagListPage findTagListPage(int page, String language) {
        String lang = safeLanguage(language);
        int requestedPage = Math.max(1, page);
        return cacheService.getOrLoad(CacheService.Keys.tagListPage(requestedPage, lang),
                        PublicTagListPage.class, CATEGORY_TAG_TTL,
                        () -> loadTagListPage(requestedPage, lang, ""))
                .orElseGet(() -> loadTagListPage(requestedPage, lang, ""));
    }

    @Transactional
    public PublicTagListPage loadTagListPage(int page, String language, String keyword) {
        String lang = safeLanguage(language);
        String safeKeyword = keyword == null ? "" : keyword.trim();
        PanacheQuery<Tag> query;
        if (safeKeyword.isBlank()) {
            query = Tag.find("order by id");
        } else {
            String pattern = "%" + safeKeyword.toLowerCase(Locale.ROOT) + "%";
            query = Tag.find("lower(cast(name as String)) like ?1 or lower(cast(description as String)) like ?1 "
                    + "or lower(slug) like ?1 order by id", pattern);
        }
        long totalCount = query.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        int currentPage = Math.min(Math.max(1, page), totalPages);
        List<Tag> tags = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        Map<Long, Long> postCounts = loadTagPostCounts(tags);
        List<PublicTagListItem> items = tags.stream()
                .map(tag -> toTagListItem(tag, lang, postCounts.getOrDefault(tag.id, 0L)))
                .sorted((left, right) -> Long.compare(right.postCount(), left.postCount()))
                .toList();
        return new PublicTagListPage(currentPage, totalPages, totalCount, items);
    }

    @Transactional
    public Optional<PublicCategoryDetailPage> findCategoryDetailPage(String slug, int page,
                                                                     String language, String regionCode) {
        String safeSlug = slug == null ? "" : slug;
        String lang = safeLanguage(language);
        String region = safeRegion(regionCode);
        int requestedPage = Math.max(1, page);
        return cacheService.getOrLoad(
                CacheService.Keys.categoryDetail(safeSlug, lang, region, requestedPage),
                PublicCategoryDetailPage.class, CATEGORY_TAG_TTL,
                () -> loadCategoryDetailPage(safeSlug, requestedPage, lang, region));
    }

    private PublicCategoryDetailPage loadCategoryDetailPage(String slug, int page,
                                                            String language, String regionCode) {
        Category entity = Category.find("slug", slug).firstResult();
        if (entity == null || !entity.enabled) {
            return null;
        }
        List<Category> children = Category.list(
                "parent.id = ?1 and enabled = true order by createdAt desc", entity.id);
        Map<Long, Long> childCounts = loadEnabledChildCounts(children);
        List<PublicCategoryListItem> childItems = children.stream()
                .map(child -> toCategoryListItem(child, language,
                        childCounts.getOrDefault(child.id, 0L)))
                .toList();

        RegionVisibility.PositionalFilter regionFilter =
                RegionVisibility.jpqlFilter(3, BlogRegion.fromCode(regionCode));
        PanacheQuery<Post> query = Post.find(
                "(category = ?1 or ?1 member of categories) and status = ?2 and deletedAt is null "
                        + "and visibility = 0 and publishedRevision is not null "
                        + "and " + regionFilter.fragment() + " "
                        + "order by publishedAt desc nulls last, createdAt desc",
                entity, PostStatus.PUBLISHED, regionFilter.regionPattern());
        long postCount = query.count();
        int totalPages = postCount == 0 ? 1 : (int) Math.ceil((double) postCount / PAGE_SIZE);
        int currentPage = Math.min(Math.max(1, page), totalPages);
        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        Map<Long, List<PublicTagRef>> tagsByPost = loadTagsByPost(posts, language);
        Map<Long, PostCoverImageHelper.CoverImageInfo> featuredInfos =
                PostCoverImageHelper.findFeaturedImageInfos(posts.stream().map(post -> post.id).toList());
        List<PublicCategoryPostItem> postItems = posts.stream()
                .map(post -> toCategoryPostItem(post, language,
                        tagsByPost.getOrDefault(post.id, List.of()), featuredInfos.get(post.id)))
                .toList();

        return new PublicCategoryDetailPage(currentPage, totalPages, postCount,
                toCategoryDetailItem(entity, language), childItems,
                buildCategoryBreadcrumbs(entity, language), postItems);
    }

    @Transactional
    public Optional<PublicTagDetailPage> findTagDetailPage(String slug, int page,
                                                           String language, String regionCode) {
        String safeSlug = slug == null ? "" : slug;
        String lang = safeLanguage(language);
        String region = safeRegion(regionCode);
        int requestedPage = Math.max(1, page);
        return cacheService.getOrLoad(
                CacheService.Keys.tagDetail(safeSlug, lang, region, requestedPage),
                PublicTagDetailPage.class, CATEGORY_TAG_TTL,
                () -> loadTagDetailPage(safeSlug, requestedPage, lang, region));
    }

    private PublicTagDetailPage loadTagDetailPage(String slug, int page,
                                                  String language, String regionCode) {
        Tag entity = Tag.find("slug", slug).firstResult();
        if (entity == null) {
            return null;
        }

        RegionVisibility.PositionalFilter regionFilter =
                RegionVisibility.jpqlFilter(2, BlogRegion.fromCode(regionCode));
        PanacheQuery<Post> query = Post.find(
                "status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null and "
                        + regionFilter.fragment() + " and id in "
                        + "(select pt.post.id from PostTag pt where pt.tag = ?3) "
                        + "order by publishedAt desc nulls last, createdAt desc",
                PostStatus.PUBLISHED, regionFilter.regionPattern(), entity);
        long totalCount = query.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        int currentPage = Math.min(Math.max(1, page), totalPages);
        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        Map<Long, List<PublicTagRef>> tagsByPost = loadTagsByPost(posts, language);
        Map<Long, PostCoverImageHelper.CoverImageInfo> featuredInfos =
                PostCoverImageHelper.findFeaturedImageInfos(posts.stream().map(post -> post.id).toList());
        List<PublicTagPostItem> postItems = posts.stream()
                .map(post -> toTagPostItem(post, language,
                        tagsByPost.getOrDefault(post.id, List.of()), featuredInfos.get(post.id)))
                .toList();

        return new PublicTagDetailPage(currentPage, totalPages, totalCount,
                toTagDetailItem(entity, language), postItems);
    }

    /**
     * Cached public link list. Region filtering happens while building the
     * snapshot, so the region is part of the key.
     */
    @Transactional
    public List<PublicLinkItem> findPublicLinks(String regionCode) {
        String region = safeRegion(regionCode);
        return cacheService.getOrLoad(CacheService.Keys.publicLinks(region),
                        new com.fasterxml.jackson.core.type.TypeReference<List<PublicLinkItem>>() {},
                        CATEGORY_TAG_TTL,
                        () -> loadPublicLinks(region))
                .orElseGet(List::of);
    }

    private List<PublicLinkItem> loadPublicLinks(String region) {
        BlogRegion blogRegion = BlogRegion.fromCode(region);
        return Link.<Link>list("status = ?1 and type != ?2 order by sortOrder asc, createdAt desc",
                        (short) 1, LinkType.EXTERNAL_ARTICLE)
                .stream()
                .filter(LinkMonitorPolicy::isVisibleOnPublicPage)
                .filter(item -> LinkMonitorPolicy.isVisibleInRegion(item, blogRegion))
                .map(item -> new PublicLinkItem(
                        item.id,
                        item.name,
                        item.url,
                        item.icon,
                        item.description,
                        item.redirectType,
                        item.target,
                        LinkMonitorPolicy.publicMonitorLabel(item),
                        item.createdAt,
                        item.type))
                .toList();
    }

    private PublicCategoryListItem toCategoryListItem(Category category, String language, long childCount) {
        return new PublicCategoryListItem(category.slug, resolveCategoryName(category, language),
                resolveCategoryDescription(category, language), safeText(category.path), childCount,
                category.postCount == null ? 0L : category.postCount, formatDate(category.createdAt));
    }

    private PublicCategoryDetailItem toCategoryDetailItem(Category category, String language) {
        long childCount = Category.count("parent.id = ?1 and enabled = true", category.id);
        return new PublicCategoryDetailItem(category.slug, resolveCategoryName(category, language),
                resolveCategoryDescription(category, language), safeText(category.path), childCount,
                category.postCount == null ? 0L : category.postCount, formatDate(category.createdAt));
    }

    private PublicTagListItem toTagListItem(Tag tag, String language, long postCount) {
        return new PublicTagListItem(tag.slug, resolveTagName(tag, language),
                resolveTagDescription(tag, language), postCount, formatDate(tag.createdAt));
    }

    private PublicTagDetailItem toTagDetailItem(Tag tag, String language) {
        return new PublicTagDetailItem(tag.slug, resolveTagName(tag, language),
                resolveTagDescription(tag, language), formatDate(tag.createdAt));
    }

    private PublicCategoryPostItem toCategoryPostItem(Post post, String language, List<PublicTagRef> tags,
                                                      PostCoverImageHelper.CoverImageInfo featuredInfo) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, language);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, language);
        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "暂无摘要";
        }
        OffsetDateTime date = post.publishedAt != null ? post.publishedAt : post.createdAt;
        List<PublicCategoryRef> categories = categoryRefs(post, language);
        PostCoverImageHelper.CoverImageInfo coverInfo =
                PostCoverImageHelper.resolveCoverImageInfo(post, language, featuredInfo);
        return new PublicCategoryPostItem(post.slug, title, summary, formatDate(date),
                post.category != null
                        ? LanguageHelper.resolveLocalizedValue(post.category.name, language) : "未分类",
                categories, tags, coverInfo.url(), coverInfo.thumbhash());
    }

    private PublicTagPostItem toTagPostItem(Post post, String language, List<PublicTagRef> tags,
                                            PostCoverImageHelper.CoverImageInfo featuredInfo) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, language);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, language);
        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "No summary available.";
        }
        OffsetDateTime date = post.publishedAt != null ? post.publishedAt : post.createdAt;
        List<PublicCategoryRef> categories = categoryRefs(post, language);
        PostCoverImageHelper.CoverImageInfo coverInfo =
                PostCoverImageHelper.resolveCoverImageInfo(post, language, featuredInfo);
        return new PublicTagPostItem(post.slug, title, summary, formatDate(date),
                post.category != null
                        ? LanguageHelper.resolveLocalizedValue(post.category.name, language) : "未分类",
                categories, tags, coverInfo.url(), coverInfo.thumbhash());
    }

    private List<PublicCategoryRef> categoryRefs(Post post, String language) {
        List<PublicCategoryRef> categories = post.categories == null ? new ArrayList<>()
                : post.categories.stream()
                        .filter(category -> category != null && category.enabled)
                        .map(category -> new PublicCategoryRef(
                                LanguageHelper.resolveLocalizedValue(category.name, language),
                                category.slug))
                        .toList();
        if (categories.isEmpty() && post.category != null && post.category.enabled) {
            categories = List.of(new PublicCategoryRef(
                    LanguageHelper.resolveLocalizedValue(post.category.name, language),
                    post.category.slug));
        }
        return categories;
    }

    private Map<Long, List<PublicTagRef>> loadTagsByPost(List<Post> posts, String language) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<Long> postIds = posts.stream().map(post -> post.id).toList();
        Map<Long, List<PublicTagRef>> tagsByPost = new java.util.HashMap<>();
        List<PostTag> postTags = PostTag.find("post.id in ?1", postIds).list();
        for (PostTag postTag : postTags) {
            tagsByPost.computeIfAbsent(postTag.post.id, ignored -> new ArrayList<>())
                    .add(new PublicTagRef(
                            LanguageHelper.resolveLocalizedValue(postTag.tag.name, language),
                            postTag.tag.slug));
        }
        return tagsByPost;
    }

    private Map<Long, Long> loadEnabledChildCounts(List<Category> categories) {
        if (categories.isEmpty()) {
            return Map.of();
        }
        List<Long> categoryIds = categories.stream().map(category -> category.id).toList();
        Map<Long, Long> childCounts = new java.util.HashMap<>();
        List<Object[]> rows = Category.getEntityManager()
                .createQuery("select parent.id, count(id) from Category "
                        + "where enabled = true and parent.id in ?1 group by parent.id", Object[].class)
                .setParameter(1, categoryIds)
                .getResultList();
        for (Object[] row : rows) {
            childCounts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return childCounts;
    }

    private Map<Long, Long> loadTagPostCounts(List<Tag> tags) {
        if (tags.isEmpty()) {
            return Map.of();
        }
        List<Long> tagIds = tags.stream().map(tag -> tag.id).toList();
        Map<Long, Long> postCounts = new java.util.HashMap<>();
        List<Object[]> rows = PostTag.getEntityManager().createQuery(
                        "select tag.id, count(post.id) from PostTag "
                                + "where tag.id in ?1 and post.status = ?2 and post.deletedAt is null "
                                + "and post.visibility = 0 and post.publishedRevision is not null "
                                + "group by tag.id", Object[].class)
                .setParameter(1, tagIds)
                .setParameter(2, PostStatus.PUBLISHED)
                .getResultList();
        for (Object[] row : rows) {
            postCounts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return postCounts;
    }

    private List<PublicCategoryBreadcrumb> buildCategoryBreadcrumbs(Category category, String language) {
        List<PublicCategoryBreadcrumb> items = new ArrayList<>();
        String path = safeText(category.path);
        if (path.isBlank()) {
            items.add(new PublicCategoryBreadcrumb(category.slug,
                    resolveCategoryName(category, language), "/category/" + category.slug));
            return items;
        }
        for (String slug : path.split("\\.")) {
            if (slug == null || slug.isBlank()) {
                continue;
            }
            Category node = Category.find("slug = ?1 and enabled = true", slug).firstResult();
            if (node != null) {
                items.add(new PublicCategoryBreadcrumb(node.slug,
                        resolveCategoryName(node, language), "/category/" + node.slug));
            }
        }
        return items;
    }

    private String resolveCategoryName(Category category, String language) {
        String name = LanguageHelper.resolveLocalizedValue(category.name, language);
        return name == null || name.isBlank() ? category.slug : name;
    }

    private String resolveCategoryDescription(Category category, String language) {
        return LanguageHelper.resolveLocalizedValue(category.description, language);
    }

    private String resolveTagName(Tag tag, String language) {
        String name = LanguageHelper.resolveLocalizedValue(tag.name, language);
        return name == null || name.isBlank() ? tag.slug : name;
    }

    private String resolveTagDescription(Tag tag, String language) {
        return LanguageHelper.resolveLocalizedValue(tag.description, language);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
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

    private PublicPostSnapshot toPublicPostSnapshot(Post post) {
        Map<String, String> previewByLanguage = new LinkedHashMap<>();
        // One price read serves both the preview decision and the snapshot field.
        long postPrice = postAccessService.getPostPrice(post);
        if (post.publishedRevision != null && post.publishedRevision.contentMarkdown != null) {
            boolean contentRequiresUnlock = requiresProtectedPreview(post, postPrice);
            int freeLines = postAccessService.getFreeLines(post);
            // Provenance, not content: only revisions applied from a primary sync
            // payload already hold the rendered preview. Everything else is
            // authored content and must be rendered and truncated here.
            boolean previewAlreadyRendered = post.publishedRevision.publicPreviewRendered;
            for (Map.Entry<String, String> entry : post.publishedRevision.contentMarkdown.entrySet()) {
                String preview = previewAlreadyRendered
                        ? entry.getValue()
                        : postAccessService.getPreviewOnlyContent(
                                entry.getValue(), freeLines, contentRequiresUnlock, post.id, postPrice, null,
                                post.renderType);
                previewByLanguage.put(entry.getKey(), preview);
            }
        }
        String categorySlug = post.category == null ? null : post.category.slug;
        Map<String, String> categoryName = post.category == null
                ? Map.of() : copyMap(post.category.name);
        List<PublicCategorySnapshot> categories = post.categories == null ? new ArrayList<>()
                : post.categories.stream().filter(category -> category != null && category.enabled)
                        .map(category -> new PublicCategorySnapshot(copyMap(category.name), category.slug)).toList();
        if (categories.isEmpty() && post.category != null && post.category.enabled) {
            categories = List.of(new PublicCategorySnapshot(categoryName, categorySlug));
        }
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
                categories,
                resolveAuthorName(post, Map.of()),
                post.publishedAt,
                post.updatedAt,
                post.viewCount,
                post.featured,
                post.allowComment,
                post.visibility,
                postPrice,
                tags,
                attachments,
                copyList(post.visibilityRegions),
                copyList(post.contentDeclarations),
                repostPolicyCatalog.resolve(post).code(),
                resolveRelatedStoreItems(post.extraInfo),
                post.articleMode == null ? com.biliwind.blog.model.ArticleMode.STANDARD : post.articleMode,
                post.tutorialMeta,
                post.resourceMeta);
    }

    private List<PublicPostListSnapshot> toPublicPostListSnapshots(List<Post> posts, String language) {
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

        Map<Long, PostCoverImageHelper.CoverImageInfo> featuredInfos = PostCoverImageHelper.findFeaturedImageInfos(postIds);
        Map<Long, String> authorNames = loadAuthorNames(posts);
        List<PublicPostListSnapshot> result = new ArrayList<>();
        for (Post post : posts) {
            Map<String, String> categoryName = post.category == null
                    ? Map.of() : copyMap(post.category.name);
            String categorySlug = post.category == null ? null : post.category.slug;
            List<PublicCategorySnapshot> categories = post.categories == null ? new ArrayList<>()
                    : post.categories.stream().filter(category -> category != null && category.enabled)
                            .map(category -> new PublicCategorySnapshot(copyMap(category.name), category.slug)).toList();
            if (categories.isEmpty() && post.category != null && post.category.enabled) {
                categories = List.of(new PublicCategorySnapshot(categoryName, categorySlug));
            }
            PostCoverImageHelper.CoverImageInfo coverInfo =
                    PostCoverImageHelper.resolveCoverImageInfo(post, language, featuredInfos.get(post.id));
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
                    categories,
                    resolveAuthorName(post, authorNames),
                    tagsByPost.getOrDefault(post.id, List.of()),
                    copyList(post.visibilityRegions),
                    coverInfo.url(),
                    coverInfo.thumbhash(),
                    post.articleMode == null ? com.biliwind.blog.model.ArticleMode.STANDARD : post.articleMode,
                    post.tutorialMeta,
                    post.resourceMeta));
        }
        return result;
    }

    /** Resolves author labels for a page of posts with one query instead of one per post. */
    private Map<Long, String> loadAuthorNames(List<Post> posts) {
        java.util.Set<Long> userIds = new java.util.HashSet<>();
        for (Post post : posts) {
            if (post.user != null && (post.authorName == null || post.authorName.isBlank())) {
                userIds.add(post.user.id);
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        for (User user : User.<User>find("id in ?1", userIds).list()) {
            names.put(user.id, user.username);
        }
        return names;
    }

    private String resolveAuthorName(Post post, Map<Long, String> authorNames) {
        if (post != null && post.authorName != null && !post.authorName.isBlank()) {
            return post.authorName.trim();
        }
        if (post == null || post.user == null) {
            return resolveWespAuthorName(post);
        }
        String prefetched = authorNames.get(post.user.id);
        if (prefetched != null && !prefetched.isBlank()) {
            return prefetched;
        }
        try {
            return post.user.username == null ? "Unknown" : post.user.username;
        } catch (EntityNotFoundException exception) {
            // Public WESP snapshots intentionally do not replicate credentials.
            // They carry the author's visible name in the article snapshot instead.
            LOG.warnf("公开文章作者在边缘节点不存在: postId=%s, userId=%s", post.id, post.user.id);
            return resolveWespAuthorName(post);
        }
    }

    private String resolveWespAuthorName(Post post) {
        if (post != null && post.extraInfo instanceof Map<?, ?> extraInfo) {
            Object value = extraInfo.get(WESP_PUBLIC_AUTHOR_NAME_KEY);
            if (value instanceof String authorName && !authorName.isBlank()) {
                return authorName;
            }
        }
        return "Unknown";
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

    @RegisterForReflection
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
            Map<String, String> seoTitle,
            Map<String, String> seoKeywords,
            Map<String, String> seoDescription,
            Map<String, String> categoryName,
            String categorySlug,
            List<PublicCategorySnapshot> categories,
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
            List<PublicStoreItemSnapshot> relatedStoreItems,
            com.biliwind.blog.model.ArticleMode articleMode,
            com.biliwind.blog.model.TutorialMeta tutorialMeta,
            com.biliwind.blog.model.ResourceMeta resourceMeta) {

        public PublicPostSnapshot(
                Long postId,
                String slug,
                Long publishedRevisionId,
                Map<String, String> title,
                Map<String, String> summary,
                Map<String, String> aiSummary,
                Short aiSummaryStatus,
                Map<String, String> previewContent,
                com.biliwind.blog.model.PostRenderType renderType,
                Map<String, String> seoTitle,
                Map<String, String> seoKeywords,
                Map<String, String> seoDescription,
                Map<String, String> categoryName,
                String categorySlug,
                List<PublicCategorySnapshot> categories,
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
            this(postId, slug, publishedRevisionId, title, summary, aiSummary, aiSummaryStatus,
                    previewContent, renderType, seoTitle, seoKeywords, seoDescription, categoryName,
                    categorySlug, categories, authorName, publishedAt, updatedAt, viewCount, featured,
                    allowComment, visibility, postPrice, tags, attachments, visibilityRegions,
                    contentDeclarations, repostPolicyCode, relatedStoreItems,
                    com.biliwind.blog.model.ArticleMode.STANDARD, null, null);
        }
    }

    @RegisterForReflection
    public record PublicStoreItemSnapshot(
            String name,
            String description,
            Long price) {
    }

    @RegisterForReflection
    public record PublicIndexPageSnapshot(
            List<PublicPostListSnapshot> posts,
            long totalPostsCount,
            long totalPages,
            String language,
            String regionCode) {
    }

    @RegisterForReflection
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
            List<PublicCategorySnapshot> categories,
            String authorName,
            List<PublicTagSnapshot> tags,
            List<String> visibilityRegions,
            String coverImageUrl,
            String coverThumbhash,
            com.biliwind.blog.model.ArticleMode articleMode,
            com.biliwind.blog.model.TutorialMeta tutorialMeta,
            com.biliwind.blog.model.ResourceMeta resourceMeta) {

        public PublicPostListSnapshot(
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
                List<PublicCategorySnapshot> categories,
                String authorName,
                List<PublicTagSnapshot> tags,
                List<String> visibilityRegions,
                String coverImageUrl,
                String coverThumbhash) {
            this(slug, title, summary, aiSummary, aiSummaryStatus, publishedAt, updatedAt, createdAt,
                    categoryName, categorySlug, categories, authorName, tags, visibilityRegions,
                    coverImageUrl, coverThumbhash, com.biliwind.blog.model.ArticleMode.STANDARD, null, null);
        }

        public PublicPostListSnapshot(
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
                List<PublicCategorySnapshot> categories,
                String authorName,
                List<PublicTagSnapshot> tags,
                List<String> visibilityRegions,
                String coverImageUrl) {
            this(slug, title, summary, aiSummary, aiSummaryStatus, publishedAt, updatedAt, createdAt,
                    categoryName, categorySlug, categories, authorName, tags, visibilityRegions,
                    coverImageUrl, null, com.biliwind.blog.model.ArticleMode.STANDARD, null, null);
        }
    }

    @RegisterForReflection
    public record PublicCategoryListPage(
            int currentPage,
            int totalPages,
            long totalCount,
            List<PublicCategoryListItem> categories) {
    }

    @RegisterForReflection
    public record PublicTagListPage(
            int currentPage,
            int totalPages,
            long totalCount,
            List<PublicTagListItem> tags) {
    }

    @RegisterForReflection
    public record PublicCategoryDetailPage(
            int currentPage,
            int totalPages,
            long postCount,
            PublicCategoryDetailItem category,
            List<PublicCategoryListItem> children,
            List<PublicCategoryBreadcrumb> breadcrumbs,
            List<PublicCategoryPostItem> posts) {
    }

    @RegisterForReflection
    public record PublicTagDetailPage(
            int currentPage,
            int totalPages,
            long postCount,
            PublicTagDetailItem tag,
            List<PublicTagPostItem> posts) {
    }

    @RegisterForReflection
    public record PublicCategoryListItem(
            String slug,
            String name,
            String description,
            String path,
            long childCount,
            long postCount,
            String createdAtText) {
    }

    @RegisterForReflection
    public record PublicCategoryDetailItem(
            String slug,
            String name,
            String description,
            String path,
            long childCount,
            long postCount,
            String createdAtText) {
    }

    @RegisterForReflection
    public record PublicCategoryBreadcrumb(String slug, String name, String url) {
    }

    @RegisterForReflection
    public record PublicTagListItem(
            String slug,
            String name,
            String description,
            long postCount,
            String createdAtText) {
    }

    @RegisterForReflection
    public record PublicTagDetailItem(
            String slug,
            String name,
            String description,
            String createdAtText) {
    }

    @RegisterForReflection
    public record PublicLinkItem(
            Long id,
            String name,
            String url,
            String icon,
            String description,
            short redirectType,
            String target,
            String publicMonitorLabel,
            OffsetDateTime createdAt,
            LinkType type) {
    }

    @RegisterForReflection
    public record PublicCategoryRef(String name, String slug) {
    }

    @RegisterForReflection
    public record PublicTagRef(String name, String slug) {
    }

    @RegisterForReflection
    public record PublicCategoryPostItem(
            String slug,
            String title,
            String summary,
            String publishedAtText,
            String categoryName,
            List<PublicCategoryRef> categories,
            List<PublicTagRef> tags,
            String coverImageUrl,
            String coverThumbhash) {
    }

    @RegisterForReflection
    public record PublicTagPostItem(
            String slug,
            String title,
            String summary,
            String publishedAtText,
            String categoryName,
            List<PublicCategoryRef> categories,
            List<PublicTagRef> tags,
            String coverImageUrl,
            String coverThumbhash) {
    }

    @RegisterForReflection
    public record PublicTagSnapshot(
            Map<String, String> name,
            String slug) {
    }

    @RegisterForReflection
    public record PublicCategorySnapshot(
            Map<String, String> name,
            String slug) {
    }

    @RegisterForReflection
    public record PublicAttachmentSnapshot(
            String fileName,
            Long size,
            List<String> visibilityRegions,
            List<String> hiddenRegions) {
    }

    @RegisterForReflection
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
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_CATEGORIES + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
        cacheService.deletePattern(CacheService.Keys.SEARCH_CACHE_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.PUBLIC_CATEGORY_LIST_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.PUBLIC_TAG_LIST_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.PUBLIC_CATEGORY_DETAIL_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.PUBLIC_TAG_DETAIL_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.SITEMAP_PREFIX + "*");
    }

    private String safeLanguage(String language) {
        return language == null || language.isBlank() ? "zh" : language;
    }

    private String safeRegion(String regionCode) {
        return BlogRegion.fromCode(regionCode).getCode();
    }
}
