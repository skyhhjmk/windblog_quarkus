package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 预留访问控制服务层。
 * 当前使用 Active Record 方式，后续逐步迁移至 Service 分层。
 */
@ApplicationScoped
public class PostAccessService {

    public Post findBySlug(String slug) {
        return Post.find("slug = ?1 and deletedAt is null", slug)
                .firstResult();
    }

    public boolean isPrivate(Post post) {
        return post.visibility == 1;
    }

    public boolean isPasswordProtected(Post post) {
        return post.visibility == 2;
    }

    public boolean verifyPassword(Post post, String submittedPassword) {
        if (submittedPassword == null) {
            return false;
        }
        return submittedPassword.equals(post.password);
    }
}
