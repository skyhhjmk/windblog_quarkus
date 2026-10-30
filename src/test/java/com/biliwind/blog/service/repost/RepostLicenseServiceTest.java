package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.RepostLicense;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepostLicenseServiceTest {

    @Test
    void shouldKeepAuthorizationRequiredCopyFormat() {
        RepostLicenseService service = service();
        RepostLicense license = license(RepostPolicyCatalog.REQUEST_REQUIRED);

        RepostLicenseService.CopyContent copy = service.buildCopyContent(license, "token-required");

        assertTrue(copy.markdown.contains("本文已获得 WindBlog 转载授权。授权码：RPL-TEST"));
        assertTrue(copy.markdown.contains("商业链接：http://go.example.test/r/token-required"));
        assertTrue(copy.markdown.contains("商业链接说明："));
        assertTrue(copy.markdown.contains("wb_repost=RPL-TEST"));
        assertTrue(copy.html.contains("授权码：RPL-TEST"));
        assertTrue(copy.html.contains("repost-commercial-note"));
        assertTrue(copy.originalUrl.contains("wb_repost=RPL-TEST"));
    }

    @Test
    void shouldLocalizeAuthorizationCopyForEnglishReaders() {
        RepostLicenseService service = service();
        Post post = new Post();
        post.slug = "demo";
        post.title = Map.of("zh-cn", "示例文章", "en-US", "Demo article");
        post.repostPolicyCode = RepostPolicyCatalog.REQUEST_REQUIRED;
        RepostLicense license = new RepostLicense();
        license.code = "RPL-TEST";
        license.article = post;

        RepostLicenseService.CopyContent copy =
                service.buildCopyContent(license, "token-required", "en-US");

        assertTrue(copy.markdown.contains("This article is reposted with authorization from WindBlog. License code: RPL-TEST"));
        assertTrue(copy.markdown.contains("# Demo article"));
        assertTrue(copy.markdown.contains("Commercial link: http://go.example.test/r/token-required"));
        assertTrue(copy.markdown.contains("About the commercial link:"));
        assertTrue(copy.plainText.contains("Original URL: http://example.test/post/demo?wb_repost=RPL-TEST"));
    }

    @Test
    void shouldFallBackToPublicSiteWhenGoOriginIsNotConfigured() {
        RepostLicenseService service = service();
        service.goPublicUrl = Optional.empty();
        RepostLicense license = license(RepostPolicyCatalog.REQUEST_REQUIRED);

        RepostLicenseService.CopyContent copy = service.buildCopyContent(license, "token-fallback");

        assertTrue(copy.goUrl.startsWith("http://example.test/r/"));
    }

    @Test
    void shouldExplainThatFreePolicyRegistrationIsOptional() {
        RepostLicenseService service = service();
        RepostLicense license = license(RepostPolicyCatalog.CC_BY_NC_4_0);

        RepostLicenseService.CopyContent copy = service.buildCopyContent(license, "token-free");

        assertTrue(copy.markdown.contains("本文使用 CC BY-NC 4.0 协议，无须申请转载"));
        assertTrue(copy.markdown.contains("仅限非商业转载"));
        assertTrue(copy.markdown.contains("不是转载前置条件"));
        assertTrue(copy.markdown.contains("协议链接：https://creativecommons.org"));
        assertTrue(copy.markdown.contains("可选登记授权码：RPL-TEST"));
        assertTrue(copy.markdown.contains("追踪链接（可选）：http://go.example.test/r/token-free"));
        assertFalse(copy.markdown.contains("必须申请授权"));
    }

    private RepostLicenseService service() {
        RepostLicenseService service = new RepostLicenseService();
        service.publicSiteUrl = "http://example.test";
        service.goPublicUrl = Optional.of("http://go.example.test");
        service.repostPolicyCatalog = new RepostPolicyCatalog();
        return service;
    }

    private RepostLicense license(String policyCode) {
        Post post = new Post();
        post.slug = "demo";
        post.title = Map.of("zh-cn", "示例文章");
        post.repostPolicyCode = policyCode;

        RepostLicense license = new RepostLicense();
        license.code = "RPL-TEST";
        license.article = post;
        return license;
    }
}
