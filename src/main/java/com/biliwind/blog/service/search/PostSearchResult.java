package com.biliwind.blog.service.search;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record PostSearchResult(
        List<SearchHit> hits,
        long totalCount,
        boolean usedElasticsearch,
        boolean degraded,
        boolean alreadyPaged
) {
}
