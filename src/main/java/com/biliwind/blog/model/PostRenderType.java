package com.biliwind.blog.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章渲染类型。
 */
public enum PostRenderType {

    MARKDOWN((short) 0),
    HTML((short) 1),
    VDITOR((short) 2),
    V_BUILDER((short) 3),
    GUTENBERG((short) 4);

    private static final Map<Short, PostRenderType> CODE_MAP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PostRenderType::code, item -> item));

    private final short code;

    PostRenderType(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }

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
