package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostAuthorHelperTest {
    @Test
    void prefersExplicitAuthorNameOverOwnerUsername() {
        Post post = new Post();
        post.authorName = "GPT-5.5";
        User owner = new User();
        owner.username = "administrator";
        post.user = owner;

        assertEquals("GPT-5.5", PostAuthorHelper.displayName(post));
    }

    @Test
    void fallsBackToOwnerUsernameForOrdinaryPosts() {
        Post post = new Post();
        User owner = new User();
        owner.username = "administrator";
        post.user = owner;

        assertEquals("administrator", PostAuthorHelper.displayName(post));
    }
}
