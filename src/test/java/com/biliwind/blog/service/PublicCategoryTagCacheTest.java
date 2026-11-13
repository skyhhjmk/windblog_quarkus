package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.PostTagId;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.edge.DataSyncEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicCategoryTagCacheTest {

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    CacheService cacheService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    private Long categoryId;
    private Long seededPostId;
    private String categorySlug;
    private String tagSlug;

    @BeforeAll
    void seedCategoryTagAndPost() {
        categorySlug = "cache-category-" + UUID.randomUUID();
        tagSlug = "cache-tag-" + UUID.randomUUID();
        String categorySlugValue = categorySlug;
        String tagSlugValue = tagSlug;
        String username = "cache-author-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            User user = new User();
            user.username = username;
            user.email = username + "@example.com";
            user.password = passwordHasher.hash("cache-test-password");
            user.status = 1;
            user.roleName = "USER";
            user.subscribeArticleUpdates = false;
            user.subscribePromotions = false;
            user.persist();

            OffsetDateTime now = OffsetDateTime.now();
            Category category = new Category();
            category.slug = categorySlugValue;
            category.name = Map.of("zh-cn", "缓存分类", "en-US", "Cache category");
            category.description = Map.of("zh-cn", "缓存分类描述", "en-US", "Cache category description");
            category.path = "cache_" + categorySlugValue.replace("-", "_");
            category.postCount = 0L;
            category.enabled = true;
            category.createdAt = now;
            category.updatedAt = now;
            category.persist();
            categoryId = category.id;

            Tag tag = new Tag();
            tag.slug = tagSlugValue;
            tag.name = Map.of("zh-cn", "缓存标签", "en-US", "Cache tag");
            tag.description = Map.of("zh-cn", "缓存标签描述", "en-US", "Cache tag description");
            tag.createdAt = now;
            tag.updatedAt = now;
            tag.persist();

            Post post = new Post();
            post.slug = "cache-post-" + UUID.randomUUID();
            post.title = Map.of("zh-cn", "缓存测试文章", "en-US", "Cache test post");
            post.summary = Map.of("zh-cn", "缓存测试摘要", "en-US", "Cache test summary");
            post.status = PostStatus.PUBLISHED;
            post.visibility = 0;
            post.renderType = PostRenderType.MARKDOWN;
            post.user = user;
            post.category = category;
            post.categories.add(category);
            post.publishedAt = now;
            post.createdAt = now;
            post.updatedAt = now;
            post.persist();
            seededPostId = post.id;

            PostRevision revision = new PostRevision();
            revision.post = post;
            revision.title = post.title;
            revision.contentMarkdown = Map.of("zh-cn", "缓存测试正文", "en-US", "Cache test body");
            revision.editorType = 0;
            revision.revisionNumber = 1;
            revision.createdBy = user;
            revision.createdAt = now;
            revision.persist();
            post.currentRevision = revision;
            post.publishedRevision = revision;

            PostTag postTag = new PostTag();
            postTag.id = new PostTagId(post.id, tag.id);
            postTag.post = post;
            postTag.tag = tag;
            postTag.persist();
        });
    }

    @Test
    void listPagesAreWrittenThroughTheCache() {
        PublicCacheRefreshService.PublicCategoryListPage categories =
                publicCacheRefreshService.findCategoryListPage(1, "zh-cn");
        assertTrue(categories.totalCount() >= 1);
        assertTrue(cacheService.get(CacheService.Keys.categoryListPage(1, "zh-cn"),
                PublicCacheRefreshService.PublicCategoryListPage.class).isPresent());
        assertTrue(categoryListContains(categorySlug));

        PublicCacheRefreshService.PublicTagListPage tags =
                publicCacheRefreshService.findTagListPage(1, "zh-cn");
        assertTrue(tags.totalCount() >= 1);
        assertTrue(cacheService.get(CacheService.Keys.tagListPage(1, "zh-cn"),
                PublicCacheRefreshService.PublicTagListPage.class).isPresent());
        assertTrue(tagListContains(tagSlug));
    }

    @Test
    void detailPagesAreCachedWithTheirRegionFilteredPostLists() {
        Optional<PublicCacheRefreshService.PublicCategoryDetailPage> category =
                publicCacheRefreshService.findCategoryDetailPage(categorySlug, 1, "zh-cn", "global");
        assertTrue(category.isPresent());
        assertFalse(category.orElseThrow().posts().isEmpty());
        assertTrue(cacheService.get(
                CacheService.Keys.categoryDetail(categorySlug, "zh-cn", "global", 1),
                PublicCacheRefreshService.PublicCategoryDetailPage.class).isPresent());

        Optional<PublicCacheRefreshService.PublicTagDetailPage> tag =
                publicCacheRefreshService.findTagDetailPage(tagSlug, 1, "zh-cn", "global");
        assertTrue(tag.isPresent());
        assertEquals(1, tag.orElseThrow().postCount());
        assertTrue(cacheService.get(
                CacheService.Keys.tagDetail(tagSlug, "zh-cn", "global", 1),
                PublicCacheRefreshService.PublicTagDetailPage.class).isPresent());
    }

    @Test
    void categoryDataSyncEventInvalidatesCachedListAndDetailPages() {
        assertTrue(categoryListContains(categorySlug));
        assertTrue(publicCacheRefreshService.findCategoryDetailPage(
                categorySlug, 1, "zh-cn", "global").isPresent());

        String renamed = "重命名分类-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Category category = Category.findById(categoryId);
            category.name = Map.of("zh-cn", renamed, "en-US", "Renamed category");
            category.persist();
            dataSyncEvent.fire(new DataSyncEvent("CATEGORY", category.id, "UPSERT"));
        });

        assertEquals(renamed, categoryNameFromList(categorySlug));
        assertEquals(renamed, publicCacheRefreshService
                .findCategoryDetailPage(categorySlug, 1, "zh-cn", "global")
                .orElseThrow().category().name());
    }

    @Test
    void unknownSlugIsNegativelyCachedUntilAnEventInvalidatesIt() {
        String missingSlug = "missing-category-" + UUID.randomUUID();
        assertTrue(publicCacheRefreshService.findCategoryDetailPage(
                missingSlug, 1, "zh-cn", "global").isEmpty());
        assertTrue(publicCacheRefreshService.findCategoryDetailPage(
                missingSlug, 1, "zh-cn", "global").isEmpty());

        QuarkusTransaction.requiringNew().run(() -> {
            Category category = new Category();
            category.slug = missingSlug;
            category.name = Map.of("zh-cn", "迟到的分类");
            category.path = "late_" + missingSlug.replace("-", "_");
            category.enabled = true;
            category.createdAt = OffsetDateTime.now();
            category.updatedAt = OffsetDateTime.now();
            category.persist();
        });

        assertTrue(publicCacheRefreshService.findCategoryDetailPage(
                        missingSlug, 1, "zh-cn", "global").isEmpty(),
                "the negative marker must hide a value that appeared without an event");

        QuarkusTransaction.requiringNew().run(() ->
                dataSyncEvent.fire(new DataSyncEvent("CATEGORY", null, "UPSERT")));

        assertTrue(publicCacheRefreshService.findCategoryDetailPage(
                missingSlug, 1, "zh-cn", "global").isPresent());
    }

    @Test
    void postSyncInvalidatesTheSidebarCategoryCache() {
        String key = CacheService.Keys.categories("zh-cn");
        cacheService.set(key, "stale", java.time.Duration.ofHours(1));
        assertTrue(cacheService.get(key, String.class).isPresent());

        publicCacheRefreshService.refreshPostCaches(seededPostId);

        assertTrue(cacheService.get(key, String.class).isEmpty(),
                "a post sync changes Category.postCount, so the sidebar categories must be dropped");
    }

    @Test
    void warmUpPopulatesBothLanguageListPages() {
        cacheService.deletePattern(CacheService.Keys.PUBLIC_CATEGORY_LIST_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.PUBLIC_TAG_LIST_PREFIX + "*");

        publicCacheRefreshService.warmPublicCategoryTagCaches();

        for (String language : com.biliwind.blog.common.constant.LanguageConstant.SUPPORTED_LANG_ORDER) {
            assertTrue(cacheService.get(CacheService.Keys.categoryListPage(1, language),
                    PublicCacheRefreshService.PublicCategoryListPage.class).isPresent());
            assertTrue(cacheService.get(CacheService.Keys.tagListPage(1, language),
                    PublicCacheRefreshService.PublicTagListPage.class).isPresent());
        }
    }

    private boolean categoryListContains(String slug) {
        return categoryNameFromList(slug) != null;
    }

    private String categoryNameFromList(String slug) {
        for (int page = 1; page <= 10; page++) {
            PublicCacheRefreshService.PublicCategoryListPage data =
                    publicCacheRefreshService.findCategoryListPage(page, "zh-cn");
            for (PublicCacheRefreshService.PublicCategoryListItem item : data.categories()) {
                if (item.slug().equals(slug)) {
                    return item.name();
                }
            }
            if (page >= data.totalPages()) {
                break;
            }
        }
        return null;
    }

    private boolean tagListContains(String slug) {
        for (int page = 1; page <= 10; page++) {
            PublicCacheRefreshService.PublicTagListPage data =
                    publicCacheRefreshService.findTagListPage(page, "zh-cn");
            if (data.tags().stream().anyMatch(item -> item.slug().equals(slug))) {
                return true;
            }
            if (page >= data.totalPages()) {
                break;
            }
        }
        return false;
    }
}
