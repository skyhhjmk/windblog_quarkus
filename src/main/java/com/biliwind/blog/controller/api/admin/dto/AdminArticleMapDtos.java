package com.biliwind.blog.controller.api.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

public final class AdminArticleMapDtos {

    private AdminArticleMapDtos() {
    }

    @RegisterForReflection
    public record ArticleMapNode(
            Long id,
            String slug,
            String title,
            String url,
            int depth,
            boolean fuzzy) {
    }

    @RegisterForReflection
    public record ArticleMapEdge(
            Long source,
            Long target,
            String type,
            double score) {
    }

    @RegisterForReflection
    public record ArticleMap(
            boolean enabled,
            Long rootId,
            int depth,
            List<ArticleMapNode> nodes,
            List<ArticleMapEdge> edges,
            boolean truncated,
            boolean esAvailable,
            int relatedQueries,
            int relatedCacheHits) {
    }
}
