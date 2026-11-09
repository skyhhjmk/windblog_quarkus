package com.biliwind.blog.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章模式枚举
 */
public enum ArticleMode {

    /**
     * 常规文章模式
     */
    STANDARD((short) 0, "standard"),

    /**
     * 教程文章模式
     */
    TUTORIAL((short) 1, "tutorial"),

    /**
     * 资源文章模式
     */
    RESOURCE((short) 2, "resource");

    private static final Map<Short, ArticleMode> CODE_MAP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ArticleMode::code, item -> item));

    private final short code;
    private final String value;

    ArticleMode(short code, String value) {
        this.code = code;
        this.value = value;
    }

    public short code() {
        return code;
    }

    public String value() {
        return value;
    }

    public static boolean isSupportedCode(Short code) {
        return code != null && CODE_MAP.containsKey(code);
    }

    public static ArticleMode fromCode(Short code) {
        if (code == null) {
            return STANDARD;
        }
        ArticleMode mode = CODE_MAP.get(code);
        if (mode == null) {
            return STANDARD;
        }
        return mode;
    }

    public static ArticleMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        for (ArticleMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim()) || mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return STANDARD;
    }
}
