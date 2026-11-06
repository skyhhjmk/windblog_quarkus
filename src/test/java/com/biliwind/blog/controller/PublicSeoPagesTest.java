package com.biliwind.blog.controller;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.PublicCacheRefreshService;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostIndexRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract checks for the public SEO surface: titles, canonicals, hreflang,
 * pagination, sitemaps and feeds.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicSeoPagesTest {

    private static final String SITE = "http://localhost:8080";

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    ElasticsearchPostIndexRepository postIndexRepository;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    private String chineseOnlySlug;
    private String bilingualSlug;
    private Long bilingualPostId;
    private String categorySlug;
    private String tagSlug;
    private String seededUsername;

    @BeforeAll
    void seedChineseOnlyPost() {
        chineseOnlySlug = "seo-zh-only-" + UUID.randomUUID();
        bilingualSlug = "seo-bilingual-" + UUID.randomUUID();
        categorySlug = "seo-category-" + UUID.randomUUID();
        tagSlug = "seo-tag-" + UUID.randomUUID();
        seededUsername = "seo-author-" + UUID.randomUUID();
        String categorySlugValue = categorySlug;
        String tagSlugValue = tagSlug;
        String slug = chineseOnlySlug;
        String bilingual = bilingualSlug;
        String username = seededUsername;
        QuarkusTransaction.requiringNew().run(() -> {
            User user = new User();
            user.username = username;
            user.email = username + "@example.com";
            user.password = passwordHasher.hash("seo-test-password");
            user.status = 1;
            user.roleName = "USER";
            user.subscribeArticleUpdates = false;
            user.subscribePromotions = false;
            user.persist();

            OffsetDateTime now = OffsetDateTime.now();
            Post post = new Post();
            post.slug = slug;
            post.title = Map.of("zh-cn", "仅中文的 SEO 测试文章");
            post.summary = Map.of("zh-cn", "用于验证语言回退 canonical 的摘要");
            post.seoTitle = Map.of("zh-cn", "仅中文 SEO 标题");
            post.seoDescription = Map.of("zh-cn", "仅中文 SEO 描述");
            post.seoKeywords = Map.of("zh-cn", "SEO,测试");
            post.status = PostStatus.PUBLISHED;
            post.visibility = 0;
            post.renderType = PostRenderType.MARKDOWN;
            post.user = user;
            post.publishedAt = now;
            post.createdAt = now;
            post.updatedAt = now;
            post.persist();

            PostRevision revision = new PostRevision();
            revision.post = post;
            revision.title = post.title;
            revision.contentMarkdown = Map.of("zh-cn", "这是只有中文正文的测试内容。");
            revision.editorType = 0;
            revision.revisionNumber = 1;
            revision.createdBy = user;
            revision.createdAt = now;
            revision.persist();

            post.currentRevision = revision;
            post.publishedRevision = revision;

            Category category = new Category();
            category.slug = categorySlugValue;
            category.name = Map.of("zh-cn", "SEO 分类", "en-US", "SEO category");
            category.description = Map.of("zh-cn", "分类描述", "en-US", "Category description");
            category.path = "seo_category";
            category.postCount = 0L;
            category.enabled = true;
            category.createdAt = now;
            category.updatedAt = now;
            category.persist();

            Tag tag = new Tag();
            tag.slug = tagSlugValue;
            tag.name = Map.of("zh-cn", "SEO 标签", "en-US", "SEO tag");
            tag.description = Map.of("zh-cn", "标签描述", "en-US", "Tag description");
            tag.createdAt = now;
            tag.updatedAt = now;
            tag.persist();

            Post second = new Post();
            second.slug = bilingual;
            second.title = Map.of("zh-cn", "双语 SEO 文章", "en-US", "Bilingual SEO article");
            second.summary = Map.of("zh-cn", "中文摘要", "en-US", "English summary");
            second.seoTitle = Map.of("zh-cn", "中文 SEO 标题", "en-US", "English SEO title");
            second.seoDescription = Map.of("zh-cn", "中文 SEO 描述", "en-US", "English SEO description");
            second.seoKeywords = Map.of("zh-cn", "中文,关键词", "en-US", "english,keywords");
            second.status = PostStatus.PUBLISHED;
            second.visibility = 0;
            second.renderType = PostRenderType.MARKDOWN;
            second.user = user;
            second.publishedAt = now;
            second.createdAt = now;
            second.updatedAt = now;
            second.persist();

            PostRevision secondRevision = new PostRevision();
            secondRevision.post = second;
            secondRevision.title = second.title;
            secondRevision.contentMarkdown = Map.of(
                    "zh-cn", "这是中文正文。\n\n![cover](/uploads/test-cover.webp)",
                    "en-US", "This is the English body.\n\n![cover](/uploads/test-cover.webp)");
            secondRevision.editorType = 0;
            secondRevision.revisionNumber = 1;
            secondRevision.createdBy = user;
            secondRevision.createdAt = now;
            secondRevision.persist();

            second.currentRevision = secondRevision;
            second.publishedRevision = secondRevision;
            bilingualPostId = second.id;
        });
        publicCacheRefreshService.refreshPostCaches(bilingualPostId);
    }

    @Test
    void homepageUsesSiteTitleAndSubtitle() {
        given().get("/").then().statusCode(200)
                .body(containsString("WindBlog - 极简主义者的技术博客"))
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/\""))
                .body(containsString("<meta name=\"robots\" content=\"index,follow\""))
                .body(containsString("<meta property=\"og:site_name\" content=\"WindBlog\""))
                .body(not(containsString("<link rel=\"icon\" href=\"\"")));
    }

    @Test
    void pageOneRedirectsToHomeAndOutOfRangePagesAreNotFound() {
        given().redirects().follow(false).get("/page/1").then()
                .statusCode(301)
                .header("Location", endsWith("/"));
        given().get("/page/9999").then().statusCode(404);
    }

    @Test
    void languagePrefixedPagesUseSelfCanonicalAndAlternates() {
        // The global region defaults to en-US, so the unprefixed URL is canonical.
        given().get("/en-US/tools").then().statusCode(200)
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/tools\""))
                .body(containsString("<link rel=\"alternate\" hreflang=\"en-US\" href=\"" + SITE + "/tools\""))
                .body(containsString("<link rel=\"alternate\" hreflang=\"zh-cn\" href=\"" + SITE + "/zh-cn/tools\""));

        given().get("/zh-cn/tools").then().statusCode(200)
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/zh-cn/tools\""));
    }

    @Test
    void untranslatedArticleCanonicalisesToTheContentLanguage() {
        given().get("/en-US/post/" + chineseOnlySlug).then().statusCode(200)
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/zh-cn/post/" + chineseOnlySlug + "\""))
                .body(containsString("hreflang=\"zh-cn\""))
                .body(not(containsString("rel=\"alternate\" hreflang=\"en-US\"")))
                .body(containsString("\"inLanguage\":\"zh-cn\""));

        given().get("/zh-cn/post/" + chineseOnlySlug).then().statusCode(200)
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/zh-cn/post/" + chineseOnlySlug + "\""))
                .body(containsString("hreflang=\"x-default\" href=\"" + SITE + "/zh-cn/post/" + chineseOnlySlug + "\""));
    }

    @Test
    void articleConditionalRequestsReachNotModified() {
        String etag = given().get("/post/" + chineseOnlySlug)
                .then().statusCode(200)
                .extract().header("ETag");
        assertNotNull(etag, "article responses must expose an ETag");

        given().header("If-None-Match", etag)
                .get("/post/" + chineseOnlySlug)
                .then().statusCode(304);
    }

    @Test
    void headMetadataIsOnlyDuplicatedIntoPjaxFragments() {
        given().get("/post/" + chineseOnlySlug).then().statusCode(200)
                .body(not(containsString("id=\"pjax-head-metadata\"")));

        given().header("X-PJAX", "true")
                .get("/post/" + chineseOnlySlug)
                .then().statusCode(200)
                .body(containsString("id=\"pjax-head-metadata\""))
                .body(containsString("hreflang=\"zh-cn\""))
                .body(containsString("rel=\"amphtml\""))
                .body(not(containsString("<!DOCTYPE")));
    }

    @Test
    void elasticsearchSelfHealingQueriesWork() {
        assertTrue(postIndexRepository.countPublicPublishedPosts() >= 1);
        assertNotNull(postIndexRepository.maxPublicPublishedUpdatedAt());
        assertFalse(postIndexRepository.listPublicPublishedIdsUpdatedAfter(
                java.time.OffsetDateTime.now().minusDays(1)).isEmpty());
    }

    @Test
    void localizedSeoFieldsFollowTheRenderedLanguage() {
        given().get("/zh-cn/post/" + chineseOnlySlug).then().statusCode(200)
                .body(containsString("仅中文 SEO 标题"))
                .body(containsString("content=\"仅中文 SEO 描述\""))
                .body(containsString("content=\"SEO,测试\""));

        // The English URL renders the Chinese body, so it uses the zh SEO copy.
        given().get("/en-US/post/" + chineseOnlySlug).then().statusCode(200)
                .body(containsString("仅中文 SEO 标题"))
                .body(containsString("content=\"仅中文 SEO 描述\""));

        given().get("/post/" + bilingualSlug).then().statusCode(200)
                .body(containsString("English SEO title"))
                .body(containsString("content=\"English SEO description\""))
                .body(containsString("content=\"english,keywords\""));

        given().get("/zh-cn/post/" + bilingualSlug).then().statusCode(200)
                .body(containsString("中文 SEO 标题"))
                .body(containsString("content=\"中文 SEO 描述\""))
                .body(containsString("content=\"中文,关键词\""));
    }

    @Test
    void categoryAndTagDetailsRenderAfterPjaxChanges() {
        given().get("/category/" + categorySlug).then().statusCode(200)
                .body(containsString("SEO category"))
                .body(containsString("Category description"));
        given().get("/zh-cn/category/" + categorySlug).then().statusCode(200)
                .body(containsString("SEO 分类"))
                .body(containsString("分类描述"));
        given().get("/tag/" + tagSlug).then().statusCode(200)
                .body(containsString("SEO tag"))
                .body(containsString("Tag description"));
        given().get("/zh-cn/tag/" + tagSlug).then().statusCode(200)
                .body(containsString("SEO 标签"))
                .body(containsString("标签描述"));
        given().header("X-PJAX", "true").get("/category/" + categorySlug).then().statusCode(200)
                .body(containsString("id=\"pjax-content-root\""));
        given().header("X-PJAX", "true").get("/tag/" + tagSlug).then().statusCode(200)
                .body(containsString("id=\"pjax-content-root\""));
    }

    @Test
    void robotsBlocksQuarkusManagementEndpoints() {
        given().get("/robots.txt").then().statusCode(200)
                .body(containsString("Disallow: /q/"));
        given().get("/q/health/live").then()
                .header("X-Robots-Tag", "noindex");
    }

    @Test
    void sitemapIndexIncludesTheLinkShard() {
        given().get("/sitemap.xml").then().statusCode(200)
                .body(containsString("/sitemap-links-1.xml"));
    }

    @Test
    void pjaxFragmentsCarryTheServerCanonical() {
        given().header("X-PJAX", "true").get("/").then().statusCode(200)
                .body(containsString("data-page-canonical=\"" + SITE + "/\""));
        given().header("X-PJAX", "true").get("/zh-cn/category").then().statusCode(200)
                .body(containsString("data-page-canonical=\"" + SITE + "/zh-cn/category\""));
    }

    @Test
    void sitemapListsEveryPageLanguageAndAlternates() {
        given().get("/sitemap-pages.xml").then().statusCode(200)
                .header("Cache-Control", containsString("max-age=3600"))
                .body(containsString("<loc>" + SITE + "/tools</loc>"))
                .body(containsString("<loc>" + SITE + "/zh-cn/tools</loc>"))
                .body(containsString("<xhtml:link rel=\"alternate\" hreflang=\"zh-cn\""));
    }

    @Test
    void postSitemapOnlyAdvertisesAvailableTranslations() {
        given().get("/sitemap-posts.xml").then().statusCode(200)
                .body(containsString("<loc>" + SITE + "/zh-cn/post/" + chineseOnlySlug + "</loc>"))
                .body(containsString("<xhtml:link rel=\"alternate\" hreflang=\"zh-cn\" href=\"" + SITE + "/zh-cn/post/" + chineseOnlySlug + "\"/>"))
                .body(containsString("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"" + SITE + "/zh-cn/post/" + chineseOnlySlug + "\"/>"));
    }

    @Test
    void feedUsesTheRequestLanguageAndLinksContentLanguage() {
        given().get("/feed").then().statusCode(200)
                .header("Cache-Control", containsString("max-age=3600"))
                .header("Vary", containsString("Cookie"))
                .body(containsString("<language>en-US</language>"))
                // Items fall back to the content language URL, not the English one.
                .body(containsString(SITE + "/zh-cn/post/"));
    }

    @Test
    void legalPagesHaveTheirOwnTitleAndSingleCanonical() {
        given().get("/zh-cn/terms").then().statusCode(200)
                .body(containsString("<title>用户协议"))
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/terms\""))
                .body(not(containsString("rel=\"alternate\" hreflang=")));

        given().get("/terms").then().statusCode(200)
                .body(containsString("<title>Terms of Service"));
    }

    @Test
    void categoryIndexUsesLocalizedTitleAndDescription() {
        given().get("/zh-cn/category").then().statusCode(200)
                .body(containsString("<title>分类"))
                .body(containsString("浏览 WindBlog 的全部分类，按主题查找文章。"))
                .body(containsString("<link rel=\"canonical\" href=\"" + SITE + "/zh-cn/category\""));

        given().get("/category").then().statusCode(200)
                .body(containsString("<title>Categories"))
                .body(containsString("Browse all WindBlog categories and find articles by topic."));
    }

    @Test
    void linkInterstitialIsNoindex() {
        given().get("/link/go/missing-token").then()
                .header("X-Robots-Tag", "noindex,follow");
    }

    @Test
    void articleCoverTransitionHooksRenderOnHomepageAndDetailPage() {
        given().get("/").then().statusCode(200)
                .body(containsString("data-article-transition-part=\"cover\""))
                .body(containsString("data-article-transition-slug=\"" + bilingualSlug + "\""))
                .body(containsString("/uploads/test-cover.webp"));

        given().get("/post/" + bilingualSlug).then().statusCode(200)
                .body(containsString("class=\"post-title-stage has-cover\""))
                .body(containsString("class=\"post-title-stage-content space-y-4\""))
                .body(containsString("class=\"post-header-cover\""))
                .body(containsString("data-article-transition-part=\"cover\""))
                .body(containsString("data-article-transition-slug=\"" + bilingualSlug + "\""))
                .body(containsString("class=\"post-header-cover-gradient\""))
                .body(containsString("/uploads/test-cover.webp"));
    }
}
