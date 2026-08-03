package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicCacheRefreshServiceTest {

    @Test
    void shouldTreatPasswordProtectedPostsAsLockedPreviewContent() {
        Post passwordPost = new Post();
        passwordPost.visibility = 2;

        assertTrue(PublicCacheRefreshService.requiresProtectedPreview(passwordPost, 0L));
    }

    @Test
    void shouldKeepPaidPostsInLockedPreviewMode() {
        Post publicPost = new Post();
        publicPost.visibility = 0;

        assertTrue(PublicCacheRefreshService.requiresProtectedPreview(publicPost, 1L));
        assertFalse(PublicCacheRefreshService.requiresProtectedPreview(publicPost, 0L));
    }
}
