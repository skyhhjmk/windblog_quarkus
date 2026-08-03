package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Post;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostAccessPolicyTest {

    @Test
    void shouldApplyPostVisibilityRegionsToEveryAccessPath() {
        Post post = new Post();
        post.visibilityRegions = List.of("CN");
        PostAccessPolicy policy = new PostAccessPolicy();

        assertTrue(policy.isVisibleInRegion(post, BlogRegion.CN));
        assertFalse(policy.isVisibleInRegion(post, BlogRegion.GLOBAL));
        assertFalse(policy.isVisibleInRegion(post, BlogRegion.US));
    }

    @Test
    void shouldTreatEmptyPostVisibilityRegionsAsGlobal() {
        Post post = new Post();
        PostAccessPolicy policy = new PostAccessPolicy();

        assertTrue(policy.isVisibleInRegion(post, BlogRegion.GLOBAL));
        assertTrue(policy.isVisibleInRegion(post, BlogRegion.EU));
    }
}
