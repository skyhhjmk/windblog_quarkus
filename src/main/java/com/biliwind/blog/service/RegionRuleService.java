package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.RegionRule;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.service.edge.NodeRoleService;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.inject.Inject;

/**
 * 区域规则匹配服务
 */
@ApplicationScoped
public class RegionRuleService {

    @Inject
    NodeRoleService nodeRoleService;

    @ConfigProperty(name = "windblog.region-rule.cache-seconds", defaultValue = "30")
    long cacheSeconds;

    private volatile RuleSnapshot snapshot;
    private volatile BlogRegion assignedNodeRegion;
    private volatile boolean assignedNodeRegionLoaded;

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

        String normalizedHost = normalizeHost(host);
        if (!normalizedHost.isBlank()) {
            for (RuleView rule : rules) {
                if (isRuleType(rule, "domain")
                        && normalizedHost.equals(normalizeHost(rule.pattern()))) {
                    return rule.region();
                }
            }
        }

        if (languages != null && !languages.isEmpty()) {
            for (RuleView rule : rules) {
                if (isRuleType(rule, "language")) {
                    String normalizedPattern = normalizeLanguage(rule.pattern());
                    if (normalizedPattern.isEmpty()) {
                        continue;
                    }
                    for (String lang : languages) {
                        if (normalizeLanguage(lang).contains(normalizedPattern)) {
                            return rule.region();
                        }
                    }
                }
            }
        }

        BlogRegion nodeRegion = assignedNodeRegion();
        if (nodeRegion != null) {
            return nodeRegion;
        }
        return BlogRegion.GLOBAL;
    }

    /**
     * Returns the enabled domain rules as canonical site links for the public footer.
     * A root domain and its www rule represent one site, and links always use www.
     */
    public List<SiteDomainLink> siteDomains() {
        Map<String, SiteDomainLink> sitesByDomain = new LinkedHashMap<>();
        for (RuleView rule : getRules()) {
            if (!isRuleType(rule, "domain") || rule.region() == null) {
                continue;
            }
            String domain = canonicalSiteDomain(rule.pattern());
            if (domain.isBlank()) {
                continue;
            }
            String wwwDomain = "www." + domain;
            sitesByDomain.putIfAbsent(domain, new SiteDomainLink(
                    rule.region().name() + " Area | " + wwwDomain,
                    "https://" + wwwDomain));
        }
        return List.copyOf(sitesByDomain.values());
    }

    private String canonicalSiteDomain(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "";
        }
        String candidate = pattern.trim();
        if (candidate.startsWith("*.")) {
            candidate = candidate.substring(2);
        }
        if (candidate.startsWith("@.")) {
            candidate = candidate.substring(2);
        } else if (candidate.startsWith("@")) {
            candidate = candidate.substring(1);
        }
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                return "";
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public record SiteDomainLink(String label, String url) {
    }

    private boolean isRuleType(RuleView rule, String expectedType) {
        return rule.ruleType() != null
                && expectedType.equalsIgnoreCase(rule.ruleType().trim());
    }

    private String normalizeLanguage(String language) {
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }

    private BlogRegion assignedNodeRegion() {
        if (nodeRoleService == null || !nodeRoleService.isEdgeNode()) return null;
        if (assignedNodeRegionLoaded) return assignedNodeRegion;
        synchronized (this) {
            if (!assignedNodeRegionLoaded) {
                EdgeNode localNode = EdgeNode.findByNodeId(nodeRoleService.getNodeId());
                assignedNodeRegion = localNode == null ? null : localNode.region;
                assignedNodeRegionLoaded = true;
            }
            return assignedNodeRegion;
        }
    }

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[")) {
            int closingBracket = normalized.indexOf(']');
            if (closingBracket >= 0) {
                normalized = normalized.substring(0, closingBracket + 1);
            }
        } else {
            int colon = normalized.lastIndexOf(':');
            if (colon >= 0 && normalized.indexOf(':') == colon) {
                normalized = normalized.substring(0, colon);
            }
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public void invalidate() {
        snapshot = null;
        assignedNodeRegion = null;
        assignedNodeRegionLoaded = false;
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
