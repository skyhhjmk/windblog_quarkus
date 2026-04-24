package com.biliwind.blog.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章渲染类型枚举
 */
public enum PostRenderType {

    /**
     * 通用 Markdown 渲染器
     */
    MARKDOWN((short) 0),
    /**
     * 通用 HTML 渲染器
     */
    HTML((short) 1),
    /**
     * Vditor 渲染器
     */
    VDITOR((short) 2),
    /**
     * 拖拽式构建器渲染器
     */
    V_BUILDER((short) 3),
    /**
     * WordPress 兼容古腾堡区块渲染器
     */
    GUTENBERG((short) 4),
    /**
     * Flutter Quill 渲染器
     */
    FLUTTER_QUILL((short) 5),
    /**
     * Flutter Markdown Plus 渲染器
     */
    FLUTTER_MARKDOWN_PLUS((short) 6),
    /**
     * 教程块结构渲染器
     */
    TUTORIAL_BLOCK((short) 7);

    private static final Map<Short, PostRenderType> CODE_MAP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PostRenderType::code, item -> item));

    private final short code;

    PostRenderType(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }

    /**
     * 判断给定的渲染类型代码是否支持
     *
     * @param code 渲染类型代码
     * @return boolean 是否支持此代码
     */
    public static boolean isSupportedCode(Short code) {
        return code != null && CODE_MAP.containsKey(code);
    }

    public static PostRenderType fromCode(Short code) {
        if (code == null) {
            return null;
        }
        PostRenderType renderType = CODE_MAP.get(code);
        if (renderType == null) {
            throw new IllegalArgumentException("Unsupported post render type code: " + code);
        }
        return renderType;
    }
}
