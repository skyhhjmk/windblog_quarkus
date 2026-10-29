package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminArticleMapDtos.ArticleMap;
import com.biliwind.blog.controller.api.admin.dto.AdminArticleMapDtos.ArticleMapEdge;
import com.biliwind.blog.controller.api.admin.dto.AdminArticleMapDtos.ArticleMapNode;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the admin article map: explicit Markdown/HTML link references are
 * solid edges; Elasticsearch more_like_this/fuzzy matches are dashed edges.
 * Fuzzy matches are cached per article/language for six hours.
 */
@ApplicationScoped
public class ArticleMapService {

    private static final int MAX_RELATED_QUERY_NODES = 40;
    private static final Duration RELATED_CACHE_TTL = Duration.ofHours(6);

    @Inject
    InternalLinkGraphService linkGraph;

    @Inject
    ElasticsearchPostSearchService postSearch;

    @Inject
    CacheService cacheService;

    @Inject
    CanonicalUrlService canonicalUrlService;

    public ArticleMap build(Long rootId, String language, int depth, int maxNodes, int relatedPerNode) {
        if (rootId == null) {
            throw new BadRequestException("文章 ID 不能为空");
        }
        String lang = LanguageHelper.normalizeToSupportedLang(language);
        if (lang == null) {
            lang = "zh-cn";
        }
        int safeDepth = Math.max(1, Math.min(depth, 4));
        int safeMaxNodes = Math.max(10, Math.min(maxNodes, 200));
        int safeRelated = Math.max(0, Math.min(relatedPerNode, 10));

        InternalLinkGraphService.Graph base = linkGraph.adminGraphUnchecked(
                rootId, lang, safeDepth, safeMaxNodes);
        Map<Long, ArticleMapNode> nodes = new LinkedHashMap<>();
        List<ArticleMapEdge> edges = new ArrayList<>();
        Set<String> edgesSeen = new HashSet<>();
        Set<String> explicitEdges = new HashSet<>();
        for (InternalLinkGraphService.Node node : base.nodes()) {
            nodes.put(node.id(), new ArticleMapNode(node.id(), node.slug(), node.title(),
                    node.url(), node.depth(), false));
        }
        for (InternalLinkGraphService.Edge edge : base.edges()) {
            String key = edge.source() + "->" + edge.target();
            if (!edgesSeen.add(key)) continue;
            explicitEdges.add(key);
            edges.add(new ArticleMapEdge(edge.source(), edge.target(), "LINK", 1.0));
        }

        boolean esAvailable = postSearch.isAvailable();
        int relatedQueries = 0;
        int relatedCacheHits = 0;
        boolean truncated = base.truncated();
        if (esAvailable && safeRelated > 0) {
            Map<Long, Post> fuzzyPosts = new HashMap<>();
            for (InternalLinkGraphService.Node sourceNode : base.nodes()) {
                if (relatedQueries >= MAX_RELATED_QUERY_NODES || nodes.size() >= safeMaxNodes) {
                    truncated = true;
                    break;
                }
                relatedQueries++;
                RelatedLookup lookup = relatedFor(sourceNode.id(), lang, safeRelated);
                relatedCacheHits += lookup.cacheHit() ? 1 : 0;
                if (lookup.esUnavailable()) {
                    esAvailable = false;
                    break;
                }
                for (ElasticsearchPostSearchService.RelatedPost hit : lookup.items()) {
                    if (hit.id() == null || hit.id().equals(rootId)) continue;
                    String key = sourceNode.id() + "->" + hit.id();
                    if (explicitEdges.contains(key) || !edgesSeen.add(key)) continue;
                    Post target = fuzzyPosts.computeIfAbsent(hit.id(), id -> findPublished(id));
                    if (target == null) continue;
                    if (!nodes.containsKey(hit.id())) {
                        if (nodes.size() >= safeMaxNodes) {
                            truncated = true;
                            continue;
                        }
                        String title = LanguageHelper.resolveLocalizedValue(target.title, lang);
                        nodes.put(hit.id(), new ArticleMapNode(hit.id(), target.slug,
                                title == null || title.isBlank() ? target.slug : title,
                                articleUrl(target.slug, lang), sourceNode.depth() + 1, true));
                    }
                    edges.add(new ArticleMapEdge(sourceNode.id(), hit.id(), "FUZZY",
                            Math.max(0, hit.score())));
                }
            }
        }

        return new ArticleMap(true, rootId, safeDepth, new ArrayList<>(nodes.values()),
                edges, truncated, esAvailable, relatedQueries, relatedCacheHits);
    }

    private RelatedLookup relatedFor(Long postId, String language, int limit) {
        String key = CacheService.Keys.articleMapRelated(postId, language, limit);
        Optional<List<ElasticsearchPostSearchService.RelatedPost>> cached = cacheService.get(key,
                new com.fasterxml.jackson.core.type.TypeReference<List<ElasticsearchPostSearchService.RelatedPost>>() {
                });
        if (cached.isPresent()) {
            return new RelatedLookup(cached.get(), true, false);
        }
        try {
            List<ElasticsearchPostSearchService.RelatedPost> items =
                    postSearch.findRelatedPosts(postId, language, limit);
            cacheService.set(key, items, RELATED_CACHE_TTL);
            return new RelatedLookup(items, false, false);
        } catch (Exception exception) {
            return new RelatedLookup(List.of(), false, true);
        }
    }

    private Post findPublished(Long id) {
        return Post.find("id = ?1 and status = ?2 and deletedAt is null and visibility = 0 "
                        + "and publishedRevision is not null", id, PostStatus.PUBLISHED)
                .firstResult();
    }

    private String articleUrl(String slug, String language) {
        String path = ("zh-cn".equalsIgnoreCase(language) ? "" : "/" + language)
                + "/post/" + URLEncoder.encode(slug == null ? "" : slug, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return canonicalUrlService.buildPath(path);
    }

    private record RelatedLookup(List<ElasticsearchPostSearchService.RelatedPost> items,
                                 boolean cacheHit, boolean esUnavailable) {
    }
}
