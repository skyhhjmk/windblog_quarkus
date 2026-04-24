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
        return find("id = :id and deletedAt is null", id)
                .firstResult();
    }

    public Post findVisiblePostBySlug(String slug) {
        return find("slug = :slug and deletedAt is null", slug)
                .firstResult();
    }

    public java.util.List<Post> findAllPublished() {
        return list("status = ?1 and visibility = 0 and deletedAt is null order by publishedAt desc", com.biliwind.blog.model.PostStatus.PUBLISHED);
    }

}
