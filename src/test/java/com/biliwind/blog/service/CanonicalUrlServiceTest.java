package com.biliwind.blog.service;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalUrlServiceTest {

    private CanonicalUrlService service;
    private RegionContext regionContext;

    @BeforeEach
    void setup() {
        service = new CanonicalUrlService();
        regionContext = new RegionContext();
        service.regionContext = regionContext;
        service.regionRuleService = new RegionRuleService() {
            @Override
            public String siteUrlFor(BlogRegion region) {
                return switch (region) {
                    case GLOBAL -> "https://www.example.com";
                    case CN -> "https://www.example.cn";
                    default -> "";
                };
            }
        };
        service.languageSettingsService = new LanguageSettingsService() {
            @Override
            public String defaultLanguage(BlogRegion region) {
                return BlogRegion.CN == region ? "zh-cn" : "en-US";
            }
        };
    }

    @Test
    void globalHostUsesTheGlobalDomainAndDefaultLanguage() {
        assertEquals("https://www.example.com/", service.buildLocalizedPath("/", "en-US"));
        assertEquals("https://www.example.com/zh-cn/", service.buildLocalizedPath("/", "zh-cn"));
        assertEquals("https://www.example.com/post/x", service.buildLocalizedPath("/en-US/post/x", "en-US"));
        assertEquals("en-US", service.getDefaultLanguage());
    }

    @Test
    void chineseHostUsesItsOwnDomainAndIsSelfCanonical() {
        regionContext.setCurrentRegion(BlogRegion.CN);

        assertEquals("https://www.example.cn/", service.buildLocalizedPath("/", "zh-cn"));
        assertEquals("https://www.example.cn/en-US/", service.buildLocalizedPath("/", "en-US"));
        assertEquals("https://www.example.cn/post/x", service.buildLocalizedPath("/zh-cn/post/x", "zh-cn"));
        assertEquals("https://www.example.cn/en-US/post/x", service.buildLocalizedPath("/en-US/post/x", "en-US"));
        assertEquals("https://www.example.cn/category?page=2",
                service.buildLocalizedPath("/category?page=2", "zh-cn"));
        assertEquals("zh-cn", service.getDefaultLanguage());
    }
}
