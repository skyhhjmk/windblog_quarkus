package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalUrlServiceTest {

    private CanonicalUrlService service;

    @BeforeEach
    void setup() {
        service = new CanonicalUrlService();
        service.regionRuleService = new RegionRuleService() {
            @Override
            public boolean isMultiRegionEnabled() {
                return true;
            }

            @Override
            public String globalSiteUrl() {
                return "https://www.example.com";
            }
        };
        service.languageSettingsService = new LanguageSettingsService() {
            @Override
            public String defaultLanguage(BlogRegion region) {
                return BlogRegion.GLOBAL == region ? "en-US" : "zh-cn";
            }
        };
    }

    @Test
    void regionalHostsUseTheGlobalDefaultLanguageForCanonicalPaths() {
        // The Chinese regional host serves zh-cn unprefixed, but its canonical
        // URL belongs to the global site, where en-US is unprefixed.
        assertEquals("https://www.example.com/zh-cn/", service.buildLocalizedPath("/", "zh-cn"));
        assertEquals("https://www.example.com/", service.buildLocalizedPath("/", "en-US"));
        assertEquals("https://www.example.com/zh-cn/post/x", service.buildLocalizedPath("/zh-cn/post/x", "zh-cn"));
        assertEquals("https://www.example.com/post/x", service.buildLocalizedPath("/en-US/post/x", "en-US"));
        assertEquals("https://www.example.com/zh-cn/category?page=2",
                service.buildLocalizedPath("/category?page=2", "zh-cn"));
        assertEquals("en-US", service.getDefaultLanguage());
    }
}
