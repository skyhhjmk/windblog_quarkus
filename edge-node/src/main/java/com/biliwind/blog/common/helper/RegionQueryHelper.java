package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.BlogRegion;

/**
 * 区域过滤查询的工具类，统一 JPQL 片段和参数构建
 */
public final class RegionQueryHelper {

    private RegionQueryHelper() {
    }

    /**
     * visibilityRegions 为 null 或包含当前区域的通用过滤条件
     */
    public static String visibilityRegionFilterJpql() {
        return "(visibilityRegions is null or cast(visibilityRegions as String) like :regionPattern)";
    }

    /**
     * 构建用于 like 查询的区域匹配模式，如 "\"cn\""
     */
    public static String buildRegionPattern(BlogRegion region) {
        if (region == null) {
            region = BlogRegion.GLOBAL;
        }
        return "\"" + region.getCode() + "\"";
    }

    /**
     * 构建用于 like 查询的区域匹配模式（字符串版本）
     */
    public static String buildRegionPattern(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            regionCode = BlogRegion.GLOBAL.getCode();
        }
        return "\"" + regionCode + "\"";
    }
}
