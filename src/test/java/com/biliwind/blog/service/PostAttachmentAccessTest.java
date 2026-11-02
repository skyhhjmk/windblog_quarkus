package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostMediaId;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A [hide-attachment] block without a price is a login-gated attachment, not a
 * public file. Priced blocks still follow the block purchase rules.
 */
@QuarkusTest
class PostAttachmentAccessTest {

    @Inject
    PostAccessService postAccessService;

    @Inject
    MediaAccessPolicy mediaAccessPolicy;

    @Test
    @Transactional
    void freeAttachmentRequiresLoginAndProtectsTheRawUploadUrl() {
        User author = createUser("author");
        User reader = createUser("reader");
        String storageKey = "free-attachment-" + System.nanoTime() + ".zip";
        Media media = createMedia(storageKey);
        Post post = createPost(author, Map.of("zh-cn",
                "[hide-attachment id=\"free-attachment\" show=\"ruler.zip\"]"
                        + "[ruler.zip](/uploads/" + storageKey + ")"
                        + "[/hide-attachment]"));
        createRelation(post, media, (short) 0);

        String content = post.publishedRevision.contentMarkdown.get("zh-cn");
        String preview = postAccessService.getPreviewOnlyContent(content, 0, false, post.id, 0L, null);
        assertFalse(preview.contains("/uploads/" + storageKey));
        assertTrue(preview.contains("data-login-required=\"true\""));
        assertTrue(preview.contains("登录后查看附件"));

        assertTrue(postAccessService.getUnlockedBlocks(content, -1L, false, post.id, 0L, null).isEmpty());
        Map<String, String> readerBlocks =
                postAccessService.getUnlockedBlocks(content, -1L, false, post.id, 0L, reader.id);
        assertTrue(readerBlocks.containsKey("free-attachment"));
        String readerBlock = postAccessService.rewriteProtectedMediaReferences(
                post, readerBlocks.get("free-attachment"), reader.id, false, "device-a");
        assertTrue(readerBlock.contains("/api/media/download/"));
        assertFalse(readerBlock.contains("/uploads/" + storageKey));
        String anonymousBlock = postAccessService.rewriteProtectedMediaReferences(
                post, content, null, false, null);
        assertFalse(anonymousBlock.contains("/uploads/" + storageKey));

        assertTrue(postAccessService.hasProtectedMediaReference(media.id));
        assertTrue(postAccessService.isMediaInsideHiddenAttachment(post, media));

        List<PostAccessService.AttachmentItem> anonymous =
                postAccessService.resolveAttachmentItems(post, content, null, null);
        assertEquals(1, anonymous.size());
        PostAccessService.AttachmentItem locked = anonymous.get(0);
        assertEquals("ruler.zip", locked.name());
        assertFalse(locked.unlocked());
        assertTrue(locked.loginRequired());
        assertEquals(null, locked.downloadUrl());

        List<PostAccessService.AttachmentItem> loggedIn =
                postAccessService.resolveAttachmentItems(post, content, reader.id, "device-a");
        assertEquals(1, loggedIn.size());
        assertTrue(loggedIn.get(0).unlocked());
        assertFalse(loggedIn.get(0).loginRequired());
        assertTrue(loggedIn.get(0).downloadUrl().startsWith("/api/media/download/"));

        assertTrue(mediaAccessPolicy.authorizeDownload(media, post, reader.id, BlogRegion.GLOBAL).allowed());
        assertFalse(mediaAccessPolicy.authorizeDownload(media, post, null, BlogRegion.GLOBAL).allowed());
    }

    @Test
    @Transactional
    void pricedAttachmentStillRequiresItsBlockPurchase() {
        User author = createUser("paid-author");
        User reader = createUser("paid-reader");
        String storageKey = "paid-attachment-" + System.nanoTime() + ".zip";
        Media media = createMedia(storageKey);
        Post post = createPost(author, Map.of("zh-cn",
                "[hide-attachment id=\"paid-attachment\" price=20]"
                        + "[paid.zip](/uploads/" + storageKey + ")"
                        + "[/hide-attachment]"));
        createRelation(post, media, (short) 0);

        String content = post.publishedRevision.contentMarkdown.get("zh-cn");
        List<PostAccessService.AttachmentItem> items =
                postAccessService.resolveAttachmentItems(post, content, reader.id, "device-a");
        assertEquals(1, items.size());
        assertFalse(items.get(0).unlocked());
        assertFalse(items.get(0).loginRequired());
        assertEquals(20L, items.get(0).price());
        assertEquals(null, items.get(0).downloadUrl());
        assertFalse(postAccessService.canAccessProtectedMedia(post, media, reader.id));

        com.biliwind.blog.model.UserPurchaseRecord purchase =
                new com.biliwind.blog.model.UserPurchaseRecord();
        purchase.userId = reader.id;
        purchase.targetType = "POST";
        purchase.targetId = post.id;
        purchase.targetBlockId = "paid-attachment";
        purchase.pointsPaid = 20L;
        purchase.persist();

        assertTrue(postAccessService.canAccessProtectedMedia(post, media, reader.id));
        List<PostAccessService.AttachmentItem> unlocked =
                postAccessService.resolveAttachmentItems(post, content, reader.id, "device-a");
        assertTrue(unlocked.get(0).unlocked());
        assertTrue(unlocked.get(0).downloadUrl().startsWith("/api/media/download/"));
    }

    private User createUser(String prefix) {
        String suffix = System.nanoTime() + "-" + prefix;
        User user = new User();
        user.username = prefix + "-" + suffix;
        user.email = prefix + "-" + suffix + "@example.com";
        user.password = "test-password-hash";
        user.status = 1;
        user.persist();
        return user;
    }

    private Media createMedia(String storageKey) {
        Media media = new Media();
        media.storageKey = storageKey;
        media.url = "/uploads/" + storageKey;
        media.fileName = "ruler.zip";
        media.mediaType = 2;
        media.mimeType = "application/zip";
        media.size = 2048L;
        media.storageClasses = Map.of("local", Map.of("status", "READY"));
        media.version = 0;
        media.createdAt = OffsetDateTime.now();
        media.updatedAt = media.createdAt;
        media.persist();
        return media;
    }

    private Post createPost(User author, Map<String, String> content) {
        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "attachment-post-" + System.nanoTime();
        post.title = Map.of("zh-cn", "附件文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = author;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = content;
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = author;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        return post;
    }

    private void createRelation(Post post, Media media, short usageType) {
        PostMedia relation = new PostMedia();
        relation.id = new PostMediaId(post.id, media.id);
        relation.post = post;
        relation.media = media;
        relation.usageType = usageType;
        relation.persist();
    }
}
