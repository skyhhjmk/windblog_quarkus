package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionRuleServiceTest {

    @Test
    void shouldResolveDomainRulesToHttpsSiteUrls() {
        assertEquals("https://www.example.com", RegionRuleService.siteUrlFromPattern("www.example.com"));
        assertEquals("https://example.com", RegionRuleService.siteUrlFromPattern("example.com"));
        assertEquals("https://example.com", RegionRuleService.siteUrlFromPattern("https://example.com/"));
        assertEquals("https://www.example.com", RegionRuleService.siteUrlFromPattern("http://www.example.com"));
    }

    @Test
    void shouldPreferTheWwwRuleOfEachRegion() {
        List<RegionRuleService.RuleView> rules = List.of(
                new RegionRuleService.RuleView("domain", "example.cn", BlogRegion.CN),
                new RegionRuleService.RuleView("domain", "www.example.cn", BlogRegion.CN),
                new RegionRuleService.RuleView("language", "zh-cn", BlogRegion.CN),
                new RegionRuleService.RuleView("domain", "www.example.com", BlogRegion.GLOBAL));

        assertEquals("https://www.example.cn", RegionRuleService.preferredSiteUrl(rules, BlogRegion.CN));
        assertEquals("https://www.example.com", RegionRuleService.preferredSiteUrl(rules, BlogRegion.GLOBAL));
        assertEquals("", RegionRuleService.preferredSiteUrl(rules, BlogRegion.EU));
        assertEquals("https://www.example.com", RegionRuleService.preferredSiteUrl(rules, null));
    }

    @Test
    void shouldIgnoreBlankOrInvalidPatterns() {
        assertEquals("", RegionRuleService.siteUrlFromPattern(" "));
        assertEquals("", RegionRuleService.siteUrlFromPattern("https://"));
    }
}
