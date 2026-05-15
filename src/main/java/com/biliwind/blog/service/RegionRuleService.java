package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.RegionRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 区域规则匹配服务
 */
@ApplicationScoped
public class RegionRuleService {

    /**
     * 根据域名和语言解析区域
     * 优先级: 域名匹配 > 语言匹配 > 默认 global
     *
     * @param host      域名
     * @param languages Accept-Language 列表
     * @return 识别出的区域枚举
     */
    public BlogRegion resolveRegion(String host, List<String> languages) {
        List<RegionRule> rules = RegionRule.find("isEnabled = true ORDER BY priority DESC").list();

        if (host != null && !host.isBlank()) {
            for (RegionRule rule : rules) {
                if ("domain".equals(rule.ruleType) && host.equalsIgnoreCase(rule.pattern)) {
                    return rule.region;
                }
            }
        }

        if (languages != null && !languages.isEmpty()) {
            for (RegionRule rule : rules) {
                if ("language".equals(rule.ruleType)) {
                    for (String lang : languages) {
                        if (lang.toLowerCase().contains(rule.pattern.toLowerCase())) {
                            return rule.region;
                        }
                    }
                }
            }
        }

        return BlogRegion.GLOBAL;
    }
}
