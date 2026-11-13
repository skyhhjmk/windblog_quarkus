package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crawler-facing post sitemap rebuilds a 1000-post page on every request,
 * so it is read through the cache and dropped when a post changes.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SitemapCacheTest {

    @Inject
    FeedService feedService;

    @Inject
    CacheService cacheService;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    PasswordHasher passwordHasher;

    private Long postId;
    private String slug;

    @BeforeAll
    void seedPublishedPost() {
        slug = "sitemap-cache-" + UUID.randomUUID();
        String postSlug = slug;
        String username = "sitemap-author-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            User user = new User();
            user.username = username;
            user.email = username + "@example.com";
            user.password = passwordHasher.hash("sitemap-test-password");
            user.status = 1;
            user.roleName = "USER";
            user.subscribeArticleUpdates = false;
            user.subscribePromotions = false;
            user.persist();

            OffsetDateTime now = OffsetDateTime.now();
            Post post = new Post();
            post.slug = postSlug;
            post.title = Map.of("zh-cn", "站点地图缓存文章", "en-US", "Sitemap cache post");
            post.summary = Map.of("zh-cn", "站点地图缓存摘要", "en-US", "Sitemap cache summary");
            post.status = PostStatus.PUBLISHED;
            post.visibility = 0;
            post.renderType = PostRenderType.MARKDOWN;
            post.user = user;
            post.publishedAt = now;
            post.createdAt = now;
            post.updatedAt = now;
            post.persist();
            postId = post.id;

            PostRevision revision = new PostRevision();
            revision.post = post;
            revision.title = post.title;
            revision.contentMarkdown = Map.of("zh-cn", "站点地图缓存正文", "en-US", "Sitemap cache body");
            revision.editorType = 0;
            revision.revisionNumber = 1;
            revision.createdBy = user;
            revision.createdAt = now;
            revision.persist();
            post.currentRevision = revision;
            post.publishedRevision = revision;
        });
    }

    @Test
    void postSitemapIsCachedAndDroppedWhenThePostChanges() {
        // A previous test class may have warmed the sitemap before this row existed.
        publicCacheRefreshService.refreshPostCaches(postId);

        List<Integer> pages = feedService.getPostSitemapPages();
        int lastPage = pages.get(pages.size() - 1);

        List<FeedService.PostSitemapUrlView> urls = feedService.getPostSitemapUrls(lastPage);
        assertTrue(urls.stream().anyMatch(url -> url.loc().contains(slug)));
        assertTrue(cacheService.get(CacheService.Keys.sitemapPostUrls("global", lastPage),
                new TypeReference<List<FeedService.PostSitemapUrlView>>() {}).isPresent());
        assertTrue(cacheService.get(CacheService.Keys.sitemapPostPages("global"),
                new TypeReference<List<Integer>>() {}).isPresent());

        publicCacheRefreshService.refreshPostCaches(postId);

        assertTrue(cacheService.get(CacheService.Keys.sitemapPostUrls("global", lastPage),
                new TypeReference<List<FeedService.PostSitemapUrlView>>() {}).isEmpty());
        assertTrue(cacheService.get(CacheService.Keys.sitemapPostPages("global"),
                new TypeReference<List<Integer>>() {}).isEmpty());
    }
}
