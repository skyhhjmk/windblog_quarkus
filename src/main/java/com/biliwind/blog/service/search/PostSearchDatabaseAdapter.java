package com.biliwind.blog.service.search;

import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.common.helper.SearchQueryParser;
import com.biliwind.blog.controller.IndexController;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PostgreSQL adapter for search. It owns the SQL predicates and the same
 * ordering rules the ES adapter pushes into its query, so both produce the
 * same filter semantics for the same {@link PostSearchQuery}.
 */
@ApplicationScoped
public class PostSearchDatabaseAdapter {

    @PersistenceContext
    EntityManager entityManager;

    @Inject
    SearchHitMapper hitMapper;

    public SearchCandidates searchPostPage(PostSearchQuery query) {
        OffsetDateTime threshold = query.publishedAfter();
        String searchPattern = likePattern(query.rawKeyword());

        if (query.sort().isBlank()) {
            SearchCandidates candidates = postCandidates(query, query.candidateLimit());
            List<SearchHit> sorted = new ArrayList<>(candidates.hits());
            sorted.sort(SearchHitComparator.forSort(""));
            return new SearchCandidates(
                    SearchHitComparator.page(sorted, query.offset(), query.pageSize()),
                    candidates.total());
        }

        RegionVisibility.NamedFilter regionFilter =
                RegionVisibility.nativeSqlFilter("p.visibility_regions", query.region());
        Map<String, Object> params = new HashMap<>();
        String where = buildPostFilterWhere(query.parsed(), query.rawKeyword(), searchPattern,
                regionFilter, threshold, params);

        long total = count("select count(*) from posts p where " + where, params);
        jakarta.persistence.Query selectQuery = entityManager.createNativeQuery(
                "select p.* from posts p where " + where + " order by " + postOrderBy(query.sort()), Post.class);
        params.forEach(selectQuery::setParameter);
        selectQuery.setFirstResult(query.offset());
        selectQuery.setMaxResults(query.pageSize());

        @SuppressWarnings("unchecked")
        List<Post> posts = selectQuery.getResultList();
        Map<Long, List<IndexController.TagItem>> tagsByPost = hitMapper.loadTags(posts, query.lang());
        List<SearchHit> hits = new ArrayList<>();
        for (Post post : posts) {
            hits.add(hitMapper.postHit(post, query.lang(), tagsByPost, query.rawKeyword()));
        }
        return new SearchCandidates(hits, total);
    }

    /** Ordered post rows without paging, used when another source must be merged in. */
    public SearchCandidates postCandidates(PostSearchQuery query, int limit) {
        RegionVisibility.NamedFilter regionFilter =
                RegionVisibility.nativeSqlFilter("p.visibility_regions", query.region());
        Map<String, Object> params = new HashMap<>();
        String where = buildPostFilterWhere(query.parsed(), query.rawKeyword(), likePattern(query.rawKeyword()),
                regionFilter, query.publishedAfter(), params);

        long total = count("select count(*) from posts p where " + where, params);
        jakarta.persistence.Query selectQuery = entityManager.createNativeQuery(
                "select p.* from posts p where " + where + " order by " + postOrderBy(query.sort()), Post.class);
        params.forEach(selectQuery::setParameter);
        selectQuery.setMaxResults(Math.max(1, limit));
        @SuppressWarnings("unchecked")
        List<Post> posts = selectQuery.getResultList();

        Map<Long, List<IndexController.TagItem>> tagsByPost = hitMapper.loadTags(posts, query.lang());
        List<SearchHit> hits = new ArrayList<>();
        for (Post post : posts) {
            hits.add(hitMapper.postHit(post, query.lang(), tagsByPost, query.rawKeyword()));
        }
        return new SearchCandidates(hits, total);
    }

    public SearchCandidates searchTaxonomyPage(PostSearchQuery query) {
        String table = "tag".equals(query.type()) ? "tags" : "categories";
        TaxonomyWhere where = taxonomyWhere(table, query);
        long total = count("select count(*) from " + table + " where " + where.clause(), where.params());

        String sql = "select * from " + table + " where " + where.clause()
                + " order by created_at desc, id desc";
        jakarta.persistence.Query selectQuery = "tags".equals(table)
                ? entityManager.createNativeQuery(sql, Tag.class)
                : entityManager.createNativeQuery(sql, Category.class);
        for (Map.Entry<Integer, Object> entry : where.params().entrySet()) {
            selectQuery.setParameter(entry.getKey(), entry.getValue());
        }
        selectQuery.setFirstResult(query.offset());
        selectQuery.setMaxResults(query.pageSize());

        List<SearchHit> hits = new ArrayList<>();
        if ("tag".equals(query.type())) {
            @SuppressWarnings("unchecked")
            List<Tag> tags = selectQuery.getResultList();
            for (Tag tag : tags) {
                hits.add(hitMapper.tagHit(tag, query.lang()));
            }
        } else {
            @SuppressWarnings("unchecked")
            List<Category> categories = selectQuery.getResultList();
            for (Category category : categories) {
                hits.add(hitMapper.categoryHit(category, query.lang()));
            }
        }
        return new SearchCandidates(hits, total);
    }

    /** Tag/category candidates for a type=all merge, bounded by {@code limit}. */
    public SearchCandidates taxonomyCandidates(PostSearchQuery query, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        long total = 0;
        if ("all".equals(query.type())) {
            TaxonomyCandidates tags = taxonomyCandidates("tags", query, limit);
            TaxonomyCandidates categories = taxonomyCandidates("categories", query, limit);
            hits.addAll(tags.hits());
            hits.addAll(categories.hits());
            total = tags.total() + categories.total();
        } else {
            TaxonomyCandidates single = taxonomyCandidates(
                    "tag".equals(query.type()) ? "tags" : "categories", query, limit);
            hits.addAll(single.hits());
            total = single.total();
        }
        return new SearchCandidates(hits, total);
    }

    private TaxonomyCandidates taxonomyCandidates(String table, PostSearchQuery query, int limit) {
        TaxonomyWhere where = taxonomyWhere(table, query);
        long total = count("select count(*) from " + table + " where " + where.clause(), where.params());
        String sql = "select * from " + table + " where " + where.clause()
                + " order by created_at desc, id desc";
        jakarta.persistence.Query selectQuery = "tags".equals(table)
                ? entityManager.createNativeQuery(sql, Tag.class)
                : entityManager.createNativeQuery(sql, Category.class);
        for (Map.Entry<Integer, Object> entry : where.params().entrySet()) {
            selectQuery.setParameter(entry.getKey(), entry.getValue());
        }
        selectQuery.setMaxResults(Math.max(1, limit));

        List<SearchHit> hits = new ArrayList<>();
        if ("tags".equals(table)) {
            @SuppressWarnings("unchecked")
            List<Tag> tags = selectQuery.getResultList();
            for (Tag tag : tags) {
                hits.add(hitMapper.tagHit(tag, query.lang()));
            }
        } else {
            @SuppressWarnings("unchecked")
            List<Category> categories = selectQuery.getResultList();
            for (Category category : categories) {
                hits.add(hitMapper.categoryHit(category, query.lang()));
            }
        }
        return new TaxonomyCandidates(hits, total);
    }

    private TaxonomyWhere taxonomyWhere(String table, PostSearchQuery query) {
        Map<Integer, Object> params = new HashMap<>();
        params.put(1, likePattern(query.rawKeyword()));
        StringBuilder where = new StringBuilder("(lower(" + table + ".slug) like ?1 escape '\\' "
                + "or lower(" + table + ".name::text) like ?1 escape '\\' "
                + "or lower(coalesce(" + table + ".description::text, '')) like ?1 escape '\\')");
        if (query.publishedAfter() != null) {
            where.append(" and ").append(table).append(".created_at >= ?2");
            params.put(2, query.publishedAfter());
        }
        return new TaxonomyWhere(where.toString(), params);
    }

    private String buildPostFilterWhere(SearchQueryParser parsed, String keyword, String searchPattern,
                                        RegionVisibility.NamedFilter regionFilter, OffsetDateTime threshold,
                                        Map<String, Object> params) {
        StringBuilder wb = new StringBuilder("p.status = 1 and p.deleted_at is null and p.visibility = 0 "
                + "and p.published_revision_id is not null and " + regionFilter.fragment() + " ");
        params.putAll(regionFilter.parameters());

        if (parsed.hasFilters()) {
            if (threshold != null) {
                wb.append("and p.published_at >= :threshold ");
                params.put("threshold", threshold);
            }
            if (parsed.title() != null) {
                wb.append("and lower(p.title::text) like :titlePattern escape '\\' ");
                params.put("titlePattern", likePattern(parsed.title()));
            }
            if (parsed.content() != null) {
                wb.append("and (p.search_document @@ plainto_tsquery('simple', :rawContent) "
                        + "or lower(coalesce(p.summary::text, '')) like :contentPattern escape '\\') ");
                params.put("rawContent", parsed.content());
                params.put("contentPattern", likePattern(parsed.content()));
            }
            if (parsed.author() != null) {
                wb.append("and lower(coalesce(p.author_name, '')) like :authorPattern escape '\\' ");
                params.put("authorPattern", likePattern(parsed.author()));
            }
            if (parsed.user() != null) {
                wb.append("and lower(coalesce(p.author_name, '')) like :userPattern escape '\\' ");
                params.put("userPattern", likePattern(parsed.user()));
            }
            if (!parsed.freeText().isEmpty()) {
                wb.append("and (p.search_document @@ plainto_tsquery('simple', :freeText) "
                        + "or lower(p.slug) like :freePattern escape '\\' "
                        + "or lower(p.title::text) like :freePattern escape '\\' "
                        + "or lower(coalesce(p.summary::text, '')) like :freePattern escape '\\') ");
                params.put("freeText", parsed.freeText());
                params.put("freePattern", likePattern(parsed.freeText()));
            }
            return wb.toString();
        }

        params.put("keyword", keyword);
        params.put("searchPattern", searchPattern);
        wb.append("and (p.search_document @@ plainto_tsquery('simple', :keyword) "
                + "or lower(p.slug) like :searchPattern escape '\\' "
                + "or lower(p.title::text) like :searchPattern escape '\\' "
                + "or lower(coalesce(p.summary::text, '')) like :searchPattern escape '\\') ");
        if (threshold != null) {
            wb.append("and p.published_at >= :threshold ");
            params.put("threshold", threshold);
        }
        return wb.toString();
    }

    private String postOrderBy(String sort) {
        return switch (sort) {
            case "latest" -> "p.published_at DESC NULLS LAST, p.id DESC";
            case "hot" -> "p.view_count DESC NULLS LAST, p.published_at DESC NULLS LAST, p.id DESC";
            default -> "p.published_at DESC NULLS LAST, p.id DESC";
        };
    }

    private long count(String sql, Map<?, ?> params) {
        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(sql);
        params.forEach((key, value) -> {
            if (key instanceof String name) {
                countQuery.setParameter(name, value);
            } else {
                countQuery.setParameter((Integer) key, value);
            }
        });
        return ((Number) countQuery.getSingleResult()).longValue();
    }

    private String likePattern(String value) {
        return "%" + escapeLike(value == null ? "" : value.toLowerCase(Locale.ROOT)) + "%";
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record TaxonomyWhere(String clause, Map<Integer, Object> params) {
    }

    private record TaxonomyCandidates(List<SearchHit> hits, long total) {
    }
}
