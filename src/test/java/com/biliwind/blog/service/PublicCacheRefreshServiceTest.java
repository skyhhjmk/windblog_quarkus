package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
    void shouldSerializeAndDeserializePublicPostListSnapshot() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.findAndRegisterModules();
        PublicCacheRefreshService.PublicPostListSnapshot snapshot =
                new PublicCacheRefreshService.PublicPostListSnapshot(
                        "slug-1", Map.of("zh-cn", "Title"), Map.of(), Map.of(), (short) 1,
                        java.time.OffsetDateTime.now(), null, null, Map.of(), "slug",
                        java.util.List.of(), "Author", java.util.List.of(), java.util.List.of(),
                        "/uploads/cover.jpg", "someThumbHash");

        String json = mapper.writeValueAsString(snapshot);
        PublicCacheRefreshService.PublicPostListSnapshot deserialized =
                mapper.readValue(json, PublicCacheRefreshService.PublicPostListSnapshot.class);
        org.junit.jupiter.api.Assertions.assertEquals("slug-1", deserialized.slug());
        org.junit.jupiter.api.Assertions.assertEquals("someThumbHash", deserialized.coverThumbhash());
        org.junit.jupiter.api.Assertions.assertEquals("/uploads/cover.jpg", deserialized.coverImageUrl());
    }
}
