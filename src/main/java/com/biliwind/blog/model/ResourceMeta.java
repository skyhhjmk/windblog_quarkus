package com.biliwind.blog.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * 资源模式专属元数据
 */
@RegisterForReflection
public record ResourceMeta(
        String version,
        String fileSize,
        List<String> platforms,
        String officialUrl,
        String license,
        String checksumSha256,
        String checksumMd5,
        List<ResourceMirror> mirrors
) {
    public ResourceMeta {
        if (platforms == null) {
            platforms = List.of();
        }
        if (mirrors == null) {
            mirrors = List.of();
        }
    }
}
