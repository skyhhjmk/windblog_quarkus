package com.biliwind.blog.service;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicUrlServiceTest {

    @Test
    void shouldNormalizeConfiguredOriginAndBuildPaths() {
        PublicUrlService service = new PublicUrlService();
        service.configuredPublicUrl = " https://blog.example.com/// ";

        assertEquals("https://blog.example.com", service.getBaseUrl());
        assertEquals("https://blog.example.com/amp/post/hello", service.buildPath("amp/post/hello"));
    }

    @Test
    void shouldUseSafeLocalFallbackForBlankOrigin() {
        assertEquals("http://localhost:8080", PublicUrlService.normalize("  "));
    }

    @Test
    void shouldPreferTheCurrentRegionDomainRule() {
        PublicUrlService service = new PublicUrlService();
        service.configuredPublicUrl = "https://www.example.com";
        service.regionContext = regionContext(BlogRegion.CN);
        service.regionRuleService = rulesReturning("https://www.example.cn");

        assertEquals("https://www.example.cn", service.getBaseUrl());
        assertEquals("https://www.example.cn/verify", service.buildPath("verify"));
    }

    @Test
    void shouldFallBackToDeploymentOriginWhenRegionHasNoDomainRule() {
        PublicUrlService service = new PublicUrlService();
        service.configuredPublicUrl = "https://www.example.com";
        service.regionContext = regionContext(BlogRegion.CN);
        service.regionRuleService = rulesReturning("");

        assertEquals("https://www.example.com", service.getBaseUrl());
    }

    private static RegionContext regionContext(BlogRegion region) {
        RegionContext context = new RegionContext();
        context.setCurrentRegion(region);
        return context;
    }

    private static RegionRuleService rulesReturning(String url) {
        return new RegionRuleService() {
            @Override
            public String siteUrlFor(BlogRegion region) {
                return url;
            }
        };
    }
}
