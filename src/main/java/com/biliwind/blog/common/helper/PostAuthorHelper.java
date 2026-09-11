package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.Post;

/** Resolves the visible author without changing the post owner relationship. */
public final class PostAuthorHelper {
    private PostAuthorHelper() {
    }

    public static String displayName(Post post) {
        if (post == null) return "Unknown";
        if (post.authorName != null && !post.authorName.isBlank()) return post.authorName.trim();
        if (post.user != null && post.user.username != null && !post.user.username.isBlank()) {
            return post.user.username;
        }
        return "Unknown";
    }
}
