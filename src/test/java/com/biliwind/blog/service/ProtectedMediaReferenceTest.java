package com.biliwind.blog.service;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostMediaId;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProtectedMediaReferenceTest {

    @Inject
    PostAccessService postAccessService;

    @Test
    @Transactional
    void shouldHideOrReplaceProtectedMediaReferences() {
        String suffix = Long.toString(System.nanoTime());
        User user = new User();
        user.username = "protected-media-user-" + suffix;
        user.email = "protected-media-user-" + suffix + "@example.com";
        user.password = "test-password-hash";
        user.status = 1;
        user.persist();

        Post post = new Post();
        post.slug = "protected-media-" + suffix;
        post.title = Map.of("zh-cn", "受保护媒体");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = com.biliwind.blog.model.PostRenderType.MARKDOWN;
        post.user = user;
        post.createdAt = OffsetDateTime.now();
        post.updatedAt = post.createdAt;
        post.persist();

        Media media = new Media();
        media.storageKey = "private-image-" + System.nanoTime() + ".jpg";
        media.url = "/uploads/" + media.storageKey;
        media.mediaType = 0;
        media.mimeType = "image/jpeg";
        media.size = 10L;
        media.storageClasses = Map.of("local", Map.of("status", "READY"));
        media.version = 0;
        media.createdAt = OffsetDateTime.now();
        media.updatedAt = media.createdAt;
        media.persist();

        PostMedia relation = new PostMedia();
        relation.id = new PostMediaId(post.id, media.id);
        relation.post = post;
        relation.media = media;
        relation.usageType = 3;
        relation.persist();

        String content = "![private](" + media.url + ")";
        String freePreview = postAccessService.getPreviewOnlyContent(content, 0, false, post.id, 0, null);
        assertFalse(freePreview.contains(media.url));

        String preview = postAccessService.rewriteProtectedMediaReferences(
                post.id, content, null, false, null);
        assertFalse(preview.contains(media.url));

        String authorized = postAccessService.rewriteProtectedMediaReferences(
                post.id, content, 1234L, true, "device-a");
        assertFalse(authorized.contains(media.url));
        assertTrue(authorized.contains("/api/media/download/"));
    }

    @Test
    @Transactional
    void shouldProtectInlineMediaReferencedByPasswordPost() {
        String suffix = Long.toString(System.nanoTime());
        Post post = new Post();
        post.slug = "password-inline-media-" + suffix;
        post.title = Map.of("zh-cn", "密码文章正文媒体");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 2;
        post.renderType = com.biliwind.blog.model.PostRenderType.MARKDOWN;
        post.password = "hashed-password";
        post.user = User.find("status = 1").firstResult();
        post.createdAt = OffsetDateTime.now();
        post.updatedAt = post.createdAt;
        post.persist();

        Media media = new Media();
        media.storageKey = "password-inline-" + suffix + ".jpg";
        media.url = "/uploads/" + media.storageKey;
        media.mediaType = 0;
        media.mimeType = "image/jpeg";
        media.size = 10L;
        media.storageClasses = Map.of("local", Map.of("status", "READY"));
        media.version = 0;
        media.createdAt = OffsetDateTime.now();
        media.updatedAt = media.createdAt;
        media.persist();

        PostMedia relation = new PostMedia();
        relation.id = new PostMediaId(post.id, media.id);
        relation.post = post;
        relation.media = media;
        relation.usageType = 0;
        relation.persist();

        String content = "![private] (" + media.url + ")";
        String preview = postAccessService.getPreviewOnlyContent(
                content, 0, false, post.id, 0, null);

        assertFalse(preview.contains(media.url));
        assertTrue(postAccessService.hasProtectedMediaReference(media.id));

        post.visibility = 0;
        post.extraInfo = Map.of("points_price", 10);
        String paidPreview = postAccessService.getPreviewOnlyContent(
                content, 0, true, post.id, 10, null);

        assertFalse(paidPreview.contains(media.url));
        assertTrue(postAccessService.hasProtectedMediaReference(media.id));
    }
}
