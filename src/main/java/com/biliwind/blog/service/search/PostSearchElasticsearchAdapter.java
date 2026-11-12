package com.biliwind.blog.service.search;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Elasticsearch adapter for search. All ordering, date and filter decisions
 * arrive as part of the ES query, so the returned page is the engine's own
 * page over the whole match set rather than a re-sorted slice.
 */
@ApplicationScoped
public class PostSearchElasticsearchAdapter {

    @Inject
    ElasticsearchPostSearchService elasticsearchPostSearchService;

    @Inject
    SearchHitMapper hitMapper;

    @PersistenceContext
    EntityManager entityManager;

    public boolean isAvailable() {
        return elasticsearchPostSearchService.isAvailable();
    }

    public SearchCandidates searchPostPage(PostSearchQuery query) throws Exception {
        return search(query, query.offset(), query.pageSize());
    }

    /** Top candidates for a type=all merge; the caller sorts and pages the merged list. */
    public SearchCandidates postCandidates(PostSearchQuery query, int limit) throws Exception {
        return search(query, 0, Math.max(1, limit));
    }

    private SearchCandidates search(PostSearchQuery query, int from, int size) throws Exception {
        ElasticsearchPostSearchService.SearchResult searchResult =
                elasticsearchPostSearchService.searchPosts(new ElasticsearchPostSearchService.PostSearchRequest(
                        query.parsed().freeText(),
                        query.parsed().title(),
                        query.parsed().content(),
                        query.parsed().author(),
                        query.parsed().user(),
                        query.sort(),
                        query.publishedAfter(),
                        query.region().getCode(),
                        from,
                        size,
                        query.highlightText()));

        List<Long> postIds = searchResult.posts().stream()
                .map(ElasticsearchPostSearchService.SearchedPost::id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Post> postMap = postIds.isEmpty() ? Map.of() :
                entityManager.createQuery(
                                "select p from Post p left join fetch p.publishedRevision "
                                        + "left join fetch p.category where p.id in :postIds",
                                Post.class)
                        .setParameter("postIds", postIds)
                        .getResultList()
                        .stream()
                        .collect(Collectors.toMap(post -> post.id, post -> post, (first, second) -> first));

        List<SearchHit> hits = new ArrayList<>();
        for (ElasticsearchPostSearchService.SearchedPost post : searchResult.posts()) {
            Post dbPost = post.id() == null ? null : postMap.get(post.id());
            hits.add(hitMapper.indexedPostHit(dbPost, post, query.lang(), query.rawKeyword()));
        }
        return new SearchCandidates(hits, searchResult.total());
    }
}
