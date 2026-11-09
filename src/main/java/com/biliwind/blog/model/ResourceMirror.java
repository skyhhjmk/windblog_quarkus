package com.biliwind.blog.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 资源镜像配置
 */
@RegisterForReflection
public record ResourceMirror(
        String name,
        String url,
        String extractionCode,
        Boolean requiresUnlock
) {
    public ResourceMirror {
        if (requiresUnlock == null) {
            requiresUnlock = false;
        }
    }
}
