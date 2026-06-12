package com.biliwind.blog.service.security;

import jakarta.ws.rs.BadRequestException;

public enum EdgeImageVariant {
    NATIVE_MICRO("native-micro", "latest"),
    NATIVE("native", "latest-native"),
    JVM("jvm", "latest-jvm");

    private final String requestValue;
    private final String defaultTag;

    EdgeImageVariant(String requestValue, String defaultTag) {
        this.requestValue = requestValue;
        this.defaultTag = defaultTag;
    }

    public static EdgeImageVariant fromRequestValue(String requestValue) {
        if (requestValue == null || requestValue.isBlank()) {
            return NATIVE_MICRO;
        }

        for (EdgeImageVariant imageVariant : values()) {
            if (imageVariant.requestValue.equalsIgnoreCase(requestValue.trim())) {
                return imageVariant;
            }
        }

        throw new BadRequestException("不支持的镜像变体: " + requestValue);
    }

    public String getRequestValue() {
        return requestValue;
    }

    public String getDefaultTag() {
        return defaultTag;
    }
}
