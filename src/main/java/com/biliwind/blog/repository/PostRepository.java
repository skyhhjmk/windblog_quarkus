package com.biliwind.blog.repository;

import com.biliwind.blog.model.Post;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 文章数据访问仓库。
 * <p>
 * Public feed reads use the bounded methods here; protected article access
 * remains in its dedicated policy service.
 */
@ApplicationScoped
public class PostRepository implements PanacheRepositoryBase<Post, Long> {

    public Post findVisiblePostById(Long id) {
        return find("id = :id and deletedAt is null", java.util.Map.of("id", id))
                .firstResult();
    }

    public Post findVisiblePostBySlug(String slug) {
        return find("slug = :slug and deletedAt is null", java.util.Map.of("slug", slug))
                .firstResult();
    }

    public java.util.List<Post> findAllPublished() {
        return findAllPublished(500);
    }

    public java.util.List<Post> findAllPublished(int limit) {
        int safeLimit = Math.max(1, Math.min(5000, limit));
        return find("status = :status and visibility = 0 and deletedAt is null "
                        + "and publishedRevision is not null order by publishedAt desc, id desc",
                java.util.Map.of("status", com.biliwind.blog.model.PostStatus.PUBLISHED))
                .page(io.quarkus.panache.common.Page.ofSize(safeLimit))
                .list();
    }


    /**
     * 根据管理后台条件查询文章。
     *
     * @param status     状态
     * @param categoryId 分类ID
     * @param keyword    关键词（匹配 slug）
     * @return 文章查询对象
     */
    public io.quarkus.hibernate.orm.panache.PanacheQuery<Post> findAdminPosts(Short status, Long categoryId, String keyword) {
        StringBuilder where = new StringBuilder("deletedAt is null");
        java.util.Map<String, Object> parameters = new java.util.HashMap<>();

        if (status != null) {
            com.biliwind.blog.model.PostStatus postStatus = com.biliwind.blog.model.PostStatus.fromCode(status);
            if (postStatus == null) {
                where.append(" and 1 = 0");
            } else {
                where.append(" and status = :status");
                parameters.put("status", postStatus);
            }
        }
        if (categoryId != null) {
            where.append(" and category.id = :categoryId");
            parameters.put("categoryId", categoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and lower(slug) like :keyword");
            parameters.put("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }

        return find(where.toString(), io.quarkus.panache.common.Sort.by("updatedAt").descending(), parameters);
    }
}
