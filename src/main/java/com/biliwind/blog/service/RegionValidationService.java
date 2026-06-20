package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域值校验服务，确保 visibilityRegions 等列表中的值都是有效的区域 code
 */
@ApplicationScoped
public class RegionValidationService {

    /**
     * 校验并过滤 visibilityRegions 列表，只保留有效的区域 code
     */
    public List<String> validateAndFilterRegions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String region : regions) {
            if (region == null || region.isBlank()) {
                continue;
            }
            BlogRegion blogRegion = BlogRegion.fromCode(region);
            if (blogRegion != BlogRegion.GLOBAL || "global".equalsIgnoreCase(region.trim())) {
                result.add(blogRegion.getCode());
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        return result;
    }

    /**
     * 判断单个区域字符串是否有效
     */
    public boolean isValidRegion(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return false;
        }
        BlogRegion region = BlogRegion.fromCode(regionCode);
        return region != BlogRegion.GLOBAL || "global".equalsIgnoreCase(regionCode.trim());
    }
}
