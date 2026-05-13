package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RegionConstant;
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
     * @return 识别出的区域标识
     */
    public String resolveRegion(String host, List<String> languages) {
        // 1. 获取所有启用的规则，按优先级降序
        List<RegionRule> rules = RegionRule.find("isEnabled = true ORDER BY priority DESC").list();

        // 2. 域名匹配 (RuleType = 'domain')
        if (host != null && !host.isBlank()) {
            for (RegionRule rule : rules) {
                if ("domain".equals(rule.ruleType) && host.equalsIgnoreCase(rule.pattern)) {
                    return rule.region;
                }
            }
        }

        // 3. 语言匹配 (RuleType = 'language')
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

        // 4. 兜底返回 global
        return RegionConstant.GLOBAL;
    }
}
