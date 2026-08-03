package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.RegionRule;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域规则匹配服务
 */
@ApplicationScoped
public class RegionRuleService {

    @ConfigProperty(name = "windblog.region-rule.cache-seconds", defaultValue = "30")
    long cacheSeconds;

    private volatile RuleSnapshot snapshot;

    /**
     * 根据域名和语言解析区域
     * 优先级: 域名匹配 > 语言匹配 > 默认 global
     *
     * @param host      域名
     * @param languages Accept-Language 列表
     * @return 识别出的区域枚举
     */
    public BlogRegion resolveRegion(String host, List<String> languages) {
        List<RuleView> rules = getRules();

        if (host != null && !host.isBlank()) {
            for (RuleView rule : rules) {
                if ("domain".equals(rule.ruleType()) && host.equalsIgnoreCase(rule.pattern())) {
                    return rule.region();
                }
            }
        }

        if (languages != null && !languages.isEmpty()) {
            for (RuleView rule : rules) {
                if ("language".equals(rule.ruleType())) {
                    for (String lang : languages) {
                        if (lang.toLowerCase().contains(rule.pattern().toLowerCase())) {
                            return rule.region();
                        }
                    }
                }
            }
        }

        return BlogRegion.GLOBAL;
    }

    public void invalidate() {
        snapshot = null;
    }

    private List<RuleView> getRules() {
        RuleSnapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && current.expiresAt() > now) {
            return current.rules();
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && current.expiresAt() > now) {
                return current.rules();
            }
            List<RegionRule> loaded = RegionRule.find("isEnabled = true ORDER BY priority DESC").list();
            List<RuleView> rules = new ArrayList<>();
            for (RegionRule rule : loaded) {
                rules.add(new RuleView(rule.ruleType, rule.pattern, rule.region));
            }
            snapshot = new RuleSnapshot(List.copyOf(rules),
                    now + Math.max(1, cacheSeconds) * 1000L);
            return snapshot.rules();
        }
    }

    private record RuleSnapshot(List<RuleView> rules, long expiresAt) {
    }

    private record RuleView(String ruleType, String pattern, BlogRegion region) {
    }
}
