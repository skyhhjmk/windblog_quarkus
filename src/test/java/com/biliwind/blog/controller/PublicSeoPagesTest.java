package com.biliwind.blog.controller;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private String chineseOnlySlug;
    private String seededUsername;

    @BeforeAll
    void seedChineseOnlyPost() {
        chineseOnlySlug = "seo-zh-only-" + UUID.randomUUID();
        seededUsername = "seo-author-" + UUID.randomUUID();
        String slug = chineseOnlySlug;
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
        });
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
}
