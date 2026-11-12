package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.SeoPostPolicy;
import com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit coverage for the single SEO rule: no Quarkus, no PostgreSQL and no
 * HTTP request. The standard controller and the AMP service both call
 * {@link PageIdentityService#article}, so these assertions pin the answer they
 * share.
 */
class PageIdentityServiceTest {

    private static final String SITE = "https://blog.example.com";

    private PageIdentityService service;

    @BeforeEach
    void setUp() {
        CanonicalUrlService canonical = new CanonicalUrlService() {
            @Override
            public String getBaseUrl() {
                return SITE;
            }

            @Override
            public String getDefaultLanguage() {
                return "en-US";
            }

            @Override
            public String buildLocalizedPath(String path, String language) {
                String normalized = LanguageHelper.normalizeToSupportedLang(language);
                String lang = normalized == null ? getDefaultLanguage() : normalized;
                return buildPath(LanguageHelper.localizedPath(
                        LanguageHelper.stripLanguagePrefix(path), lang, getDefaultLanguage()));
            }

            @Override
            public String buildPath(String path) {
                String normalized = path == null || path.isBlank() ? "/" : path;
                return SITE + (normalized.startsWith("/") ? normalized : "/" + normalized);
            }
        };
        SeoPageService seoPages = new SeoPageService();
        seoPages.canonical = canonical;

        ConfigManager config = new ConfigManager() {
            @Override
            public Boolean getBoolean(String key, String field, Boolean defaultValue) {
                return defaultValue;
            }

            @Override
            public JsonNode getForRegion(String key, com.biliwind.blog.model.BlogRegion region) {
                return null;
            }
        };
        SeoManagementService seoManagement = new SeoManagementService();
        seoManagement.config = config;

        service = new PageIdentityService();
        service.canonical = canonical;
        service.seoPages = seoPages;
        service.seoManagement = seoManagement;
        service.configManager = config;
        service.regionContext = new RegionContext();
    }

    @Test
    void englishRequestForAChineseOnlyArticleCanonicalisesToChinese() {
        PageIdentityService.ArticleIdentity article = service.article(
                snapshot(), snapshot().previewContent(), "en-US", false, null);

        assertEquals("zh-cn", article.contentLanguage());
        assertEquals(SITE + "/zh-cn/post/seo-zh-only", article.identity().canonicalUrl());
        assertEquals(SITE + "/zh-cn/amp/post/seo-zh-only", article.identity().ampUrl());
        assertEquals(List.of("zh-cn", "x-default"), languages(article.identity().alternates()));
        assertTrue(article.identity().alternates().stream()
                .noneMatch(alternate -> "en-US".equals(alternate.language())));
    }

    @Test
    void seoTitleWinsOverTheDisplayTitleForEveryConsumer() {
        PageIdentityService.ArticleIdentity article = service.article(
                snapshot(), snapshot().previewContent(), "zh-cn", false, null);

        assertEquals("仅中文 SEO 标题", article.seoTitle());
        assertEquals("仅中文的 SEO 测试文章", article.displayTitle());
        assertEquals("仅中文 SEO 描述", article.description());
        assertEquals("SEO,测试", article.keywords());
    }

    @Test
    void noindexPostsAdvertiseNoAlternatesAndNoAmp() {
        SeoPostPolicy policy = new SeoPostPolicy();
        policy.noindex = true;
        policy.nofollow = true;

        PageIdentityService.ArticleIdentity gated = service.article(
                snapshot(), snapshot().previewContent(), "zh-cn", false, policy);

        assertEquals("noindex,nofollow", gated.identity().robots());
        assertTrue(gated.identity().alternates().isEmpty());
        assertNull(gated.identity().ampUrl());

        PageIdentityService.ArticleIdentity protectedPost = service.article(
                snapshot(), snapshot().previewContent(), "zh-cn", true, null);
        assertEquals("noindex,follow", protectedPost.identity().robots());
        assertTrue(protectedPost.identity().alternates().isEmpty());
        assertNull(protectedPost.identity().ampUrl());
    }

    @Test
    void genericPagesShareCanonicalRobotsAndAlternates() {
        PageIdentity identity = service.page("/category?page=2", "zh-cn");

        assertEquals(SITE + "/zh-cn/category?page=2", identity.canonicalUrl());
        assertEquals("index,follow", identity.robots());
        assertEquals(List.of("zh-cn", "en-US", "x-default"), languages(identity.alternates()));

        PageIdentity search = service.page("/search", "en-US");
        assertEquals("noindex,follow", search.robots());
    }

    private static List<String> languages(List<SeoPageService.Alternate> alternates) {
        return alternates.stream().map(SeoPageService.Alternate::language).toList();
    }

    private static PublicPostSnapshot snapshot() {
        Map<String, String> title = Map.of("zh-cn", "仅中文的 SEO 测试文章");
        Map<String, String> content = Map.of("zh-cn", "这是只有中文正文的测试内容。");
        return new PublicPostSnapshot(
                1L, "seo-zh-only", 1L, title,
                Map.of("zh-cn", "用于验证语言回退 canonical 的摘要"),
                Map.of(), null, content, PostRenderType.MARKDOWN,
                Map.of("zh-cn", "仅中文 SEO 标题"),
                Map.of("zh-cn", "SEO,测试"),
                Map.of("zh-cn", "仅中文 SEO 描述"),
                Map.of("zh-cn", "SEO 分类"), "seo-category", List.of(), "Author",
                OffsetDateTime.parse("2026-11-01T08:00:00+08:00"), null,
                0L, false, true, (short) 0, 0L, List.of(), List.of(), List.of(), List.of(),
                null, List.of());
    }
}
