package com.biliwind.blog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 博客业务区域枚举，统一项目中所有区域相关的定义
 */
public enum BlogRegion {

    GLOBAL("global", "全球"),
    CN("cn", "中国"),
    US("us", "美国"),
    EU("eu", "欧洲"),
    JP("jp", "日本"),
    HK("hk", "中国香港"),
    TW("tw", "中国台湾");

    private final String code;
    private final String displayName;

    BlogRegion(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 从字符串 code 转换为枚举，兼容旧数据中的 "china" 映射到 CN
     */
    @JsonCreator
    public static BlogRegion fromCode(String code) {
        if (code == null || code.isBlank()) {
            return GLOBAL;
        }
        String normalized = code.trim().toLowerCase();
        if ("china".equals(normalized)) {
            return CN;
        }
        for (BlogRegion region : values()) {
            if (region.code.equals(normalized)) {
                return region;
            }
        }
        return GLOBAL;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }
}
