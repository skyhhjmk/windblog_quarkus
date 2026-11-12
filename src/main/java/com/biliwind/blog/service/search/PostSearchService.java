package com.biliwind.blog.service.search;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.service.SecurityMetricsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The single owner of "find posts for this search intent". It picks the
 * adapter, merges the mixed content types consistently, and guarantees that
 * type/sort/date/filter semantics are applied before pagination on both
 * backends. Callers never see whether Elasticsearch was used.
 *
 * <p>Ranking is deliberately engine-native for the default (relevance) sort:
 * PostgreSQL orders by {@code SearchWeightHelper} over the row, while
 * Elasticsearch orders by Lucene {@code _score} with title/translation boosts.
 * Forcing one model would mean re-scoring the whole ES result set and throwing
 * away its analyzer, so both models stay deterministic and title-dominant and
 * the explicit {@code latest}/{@code hot} sorts are identical across adapters.
 * The remaining analyzer-level difference (token match vs SQL substring) is a
 * tested, accepted property of using two engines, not an unapplied filter.
 */
@ApplicationScoped
public class PostSearchService {

    private static final Logger log = Logger.getLogger(PostSearchService.class);

    @Inject
    PostSearchElasticsearchAdapter elasticsearchAdapter;

    @Inject
    PostSearchDatabaseAdapter databaseAdapter;

    @Inject
    SecurityMetricsService securityMetricsService;

    @Transactional
    public PostSearchResult search(PostSearchQuery query) {
        if (!query.hasKeyword()) {
            return new PostSearchResult(List.of(), 0, false, true, true);
        }
        if (query.elasticsearchAllowed() && query.supportsElasticsearch()
                && elasticsearchAdapter.isAvailable()) {
            try {
                return searchWithElasticsearch(query);
            } catch (Exception exception) {
                log.warnf("Elasticsearch 搜索失败，回退到数据库搜索: %s",
                        SensitiveMessageSanitizer.sanitize(exception.getMessage()));
                return searchWithDatabase(query);
            }
        }
        return searchWithDatabase(query);
    }

    private PostSearchResult searchWithElasticsearch(PostSearchQuery query) throws Exception {
        if ("all".equals(query.type())) {
            SearchCandidates posts = elasticsearchAdapter.postCandidates(query, query.candidateLimit());
            return mergeWithTaxonomy(query, posts, true);
        }
        SearchCandidates page = elasticsearchAdapter.searchPostPage(query);
        return new PostSearchResult(page.hits(), page.total(), true, false, true);
    }

    private PostSearchResult searchWithDatabase(PostSearchQuery query) {
        securityMetricsService.increment("search.degraded", "database_fallback");
        if (query.isTaxonomy()) {
            SearchCandidates page = databaseAdapter.searchTaxonomyPage(query);
            return new PostSearchResult(page.hits(), page.total(), false, true, true);
        }
        if ("all".equals(query.type())) {
            SearchCandidates posts = databaseAdapter.postCandidates(query, query.candidateLimit());
            return mergeWithTaxonomy(query, posts, false);
        }
        SearchCandidates page = databaseAdapter.searchPostPage(query);
        return new PostSearchResult(page.hits(), page.total(), false, true, true);
    }

    /**
     * type=all spans posts plus the taxonomy tables, which only PostgreSQL
     * holds. Both backends contribute candidates and this method applies the
     * one ordering rule and page window, so an ES outage cannot change which
     * content types appear or how they interleave.
     */
    private PostSearchResult mergeWithTaxonomy(PostSearchQuery query, SearchCandidates posts,
                                               boolean usedElasticsearch) {
        SearchCandidates taxonomy = databaseAdapter.taxonomyCandidates(query, query.candidateLimit());
        List<SearchHit> merged = new ArrayList<>(posts.hits());
        merged.addAll(taxonomy.hits());
        merged.sort(SearchHitComparator.forSort(query.sort()));
        return new PostSearchResult(
                SearchHitComparator.page(merged, query.offset(), query.pageSize()),
                posts.total() + taxonomy.total(),
                usedElasticsearch,
                !usedElasticsearch,
                true);
    }
}
