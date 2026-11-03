package com.biliwind.blog.service;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FaviconResolverServiceTest {

    private FaviconResolverService resolver;
    private ConfigManager configManager;
    private RegionContext regionContext;
    private RegionRuleService regionRuleService;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        resolver = new FaviconResolverService();
        regionContext = new RegionContext();
        resolver.regionContext = regionContext;

        configManager = new ConfigManager() {
            private ObjectNode appearanceNode = mapper.createObjectNode();

            @Override
            public com.fasterxml.jackson.databind.JsonNode get(String key) {
                if ("appearance".equals(key)) {
                    return appearanceNode;
                }
                return super.get(key);
            }

            @Override
            public String getString(String key, String field, String fallback) {
                if ("appearance".equals(key)) {
                    if (appearanceNode.has(field)) {
                        return appearanceNode.get(field).asText(fallback);
                    }
                    if (field.startsWith("favicon_url_regions.")) {
                        String sub = field.substring("favicon_url_regions.".length());
                        if (appearanceNode.has("favicon_url_regions") && appearanceNode.get("favicon_url_regions").has(sub)) {
                            return appearanceNode.get("favicon_url_regions").get(sub).asText(fallback);
                        }
                    }
                }
                return fallback;
            }

            @Override
            public String getStringForRegion(String key, String field, String fallback, BlogRegion region) {
                return getString(key, field, fallback);
            }
        };
        resolver.configManager = configManager;

        regionRuleService = new RegionRuleService() {
            @Override
            public List<String> siteDomainsCached() {
                return List.of("geekcorelab.com", "geekcorelab.cn");
            }

            @Override
            public String siteUrlFor(BlogRegion region) {
                if (region == BlogRegion.CN) {
                    return "https://www.geekcorelab.cn";
                }
                return "https://www.geekcorelab.com";
            }
        };
        resolver.regionRuleService = regionRuleService;
    }

    @Test
    void emptyConfigReturnsEmptyString() {
        assertEquals("", resolver.resolveFaviconUrl());
    }

    @Test
    void returnsDefaultFaviconWhenNoRegionOverride() {
        ObjectNode appearance = (ObjectNode) configManager.get("appearance");
        appearance.put("favicon_url", "/uploads/default-favicon.png");

        assertEquals("/uploads/default-favicon.png", resolver.resolveFaviconUrl());
    }

    @Test
    void switchesUrlWhenRegionOverrideConfigured() {
        ObjectNode appearance = (ObjectNode) configManager.get("appearance");
        appearance.put("favicon_url", "/uploads/global-favicon.png");
        ObjectNode regions = appearance.putObject("favicon_url_regions");
        regions.put("cn", "/uploads/cn-favicon.png");

        // GLOBAL region
        regionContext.setCurrentRegion(BlogRegion.GLOBAL);
        assertEquals("/uploads/global-favicon.png", resolver.resolveFaviconUrl());

        // CN region
        regionContext.setCurrentRegion(BlogRegion.CN);
        assertEquals("/uploads/cn-favicon.png", resolver.resolveFaviconUrl());
    }

    @Test
    void rewritesOwnDomainCrossRegion() {
        ObjectNode appearance = (ObjectNode) configManager.get("appearance");
        appearance.put("favicon_url", "https://www.geekcorelab.com/favicon.svg");

        // GLOBAL region keeps com
        regionContext.setCurrentRegion(BlogRegion.GLOBAL);
        assertEquals("https://www.geekcorelab.com/favicon.svg", resolver.resolveFaviconUrl());

        // CN region rewrites to cn site URL
        regionContext.setCurrentRegion(BlogRegion.CN);
        assertEquals("https://www.geekcorelab.cn/favicon.svg", resolver.resolveFaviconUrl());
    }
}
