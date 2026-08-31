package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.RepostLicense;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
        assertTrue(copy.html.contains("授权码：RPL-TEST"));
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
        service.goPublicUrl = "http://go.example.test";
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
