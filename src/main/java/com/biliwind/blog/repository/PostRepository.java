package com.biliwind.blog.repository;

import com.biliwind.blog.model.Post;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 文章数据访问仓库。
 * <p>
 * 当前阶段暂未接入调用链。
 * 后续将逐步替换 PostAccessService 中的数据库访问逻辑。
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
        return list("status = :status and visibility = 0 and deletedAt is null and publishedRevision is not null order by publishedAt desc",
                java.util.Map.of("status", com.biliwind.blog.model.PostStatus.PUBLISHED));
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
            where.append(" and status = :status");
            parameters.put("status", status);
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
