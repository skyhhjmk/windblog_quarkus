package com.biliwind.blog.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 链接类型枚举
 */
public enum LinkType {

    /**
     * 友情链接
     */
    FRIENDLY_LINK((short) 0, "友情链接"),
    /**
     * 网盘资源
     */
    CLOUD_RESOURCE((short) 1, "网盘资源"),
    /**
     * 文章内部链接
     */
    INTERNAL_ARTICLE((short) 2, "文章内部链接"),
    /**
     * 文章外部链接
     */
    EXTERNAL_ARTICLE((short) 3, "文章外部链接"),
    /**
     * 直链下载链接
     */
    DIRECT_DOWNLOAD((short) 4, "直链下载链接"),
    /**
     * 工具链接
     */
    TOOL((short) 5, "工具链接"),
    /**
     * 文档链接
     */
    DOCUMENTATION((short) 6, "文档链接"),
    /**
     * 社交媒体链接
     */
    SOCIAL_MEDIA((short) 7, "社交媒体链接"),
    /**
     * 开源项目链接
     */
    OPEN_SOURCE((short) 8, "开源项目链接"),
    /**
     * 其他链接
     */
    OTHER((short) 99, "其他链接");

    private static final Map<Short, LinkType> CODE_MAP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(LinkType::code, item -> item));

    private final short code;
    private final String description;

    LinkType(short code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 判断给定的链接类型代码是否支持
     *
     * @param code 链接类型代码
     * @return boolean 是否支持此代码
     */
    public static boolean isSupportedCode(Short code) {
        return code != null && CODE_MAP.containsKey(code);
    }

    public static LinkType fromCode(Short code) {
        if (code == null) {
            return null;
        }
        LinkType linkType = CODE_MAP.get(code);
        if (linkType == null) {
            throw new IllegalArgumentException("Unsupported link type code: " + code);
        }
        return linkType;
    }

    public short code() {
        return code;
    }
}
