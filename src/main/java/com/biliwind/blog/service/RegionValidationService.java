package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.RegionVisibility;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 区域值校验服务，写路径的统一入口：规范化大小写并拒绝未知区域 code。
 */
@ApplicationScoped
public class RegionValidationService {

    /**
     * 校验 visibilityRegions/hiddenRegions 等列表：去空白、转小写、兼容 china，
     * 未知 code 直接拒绝；全区域可见统一存为 null。
     */
    public List<String> validateAndFilterRegions(List<String> regions) {
        return RegionVisibility.normalizeForWrite(regions);
    }

    /**
     * 判断单个区域字符串是否为已知区域（允许大小写和 china 兼容写法）。
     */
    public boolean isValidRegion(String regionCode) {
        return RegionVisibility.isKnownRegion(regionCode);
    }
}
