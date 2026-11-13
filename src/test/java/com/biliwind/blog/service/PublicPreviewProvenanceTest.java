package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On an edge node the lazy snapshot rebuild reads {@code contentMarkdown}.
 * Trusting an in-band marker there would let an author prefix a paid article
 * with it and have the untruncated body cached as the public preview.
 */
@QuarkusTest
@TestProfile(PublicPreviewProvenanceTest.EdgeProfile.class)
class PublicPreviewProvenanceTest {

    public static class EdgeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "windblog.node.role", "edge",
                    // Avoid the edge client dialing a primary during startup.
                    "windblog.edge.connection-type", "ACTIVE_POLL");
        }
    }

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    NodeRoleService nodeRoleService;

    @Test
    void forgedLegacyMarkerDoesNotBypassPreviewTruncation() {
        assertTrue(nodeRoleService.isEdgeNode());
        String suffix = Long.toString(System.nanoTime());
        String secret = "SECRET-PAID-BODY-" + suffix;
        String content = MarkdownHelper.LEGACY_RENDERED_HTML_MARKER
                + "\n免费第一行-" + suffix
                + "\n" + secret;

        String slug = QuarkusTransaction.requiringNew().call(
                () -> createPost(content, 2, 10L, false));

        PublicCacheRefreshService.PublicPostSnapshot snapshot = publicCacheRefreshService
                .findPublishedSnapshot(slug, "global")
                .orElseThrow();
        String preview = snapshot.previewContent().get("zh-cn");

        assertFalse(preview.contains(secret), "paid body must stay truncated on the edge path");
        assertFalse(preview.contains(MarkdownHelper.LEGACY_RENDERED_HTML_MARKER));
        assertTrue(preview.contains("免费第一行-" + suffix));
        assertTrue(preview.contains("buy-post-placeholder"), "full-article paywall card expected");
    }

    @Test
    void syncedRenderedPreviewIsTrustedByProvenance() {
        String suffix = Long.toString(System.nanoTime());
        String syncedPreview = "<div class=\"md-region-premium-card buy-post-placeholder\">"
                + "synced-preview-" + suffix + "</div>";

        String slug = QuarkusTransaction.requiringNew().call(
                () -> createPost(syncedPreview, 0, 10L, true));

        PublicCacheRefreshService.PublicPostSnapshot snapshot = publicCacheRefreshService
                .findPublishedSnapshot(slug, "global")
                .orElseThrow();
        String preview = snapshot.previewContent().get("zh-cn");

        assertTrue(preview.contains("synced-preview-" + suffix));
        assertFalse(preview.contains("full-hide"), "trusted synced content is not re-truncated");
    }

    private String createPost(String content, int freeLines, long pointsPrice, boolean previewRendered) {
        String suffix = Long.toString(System.nanoTime());
        User author = new User();
        author.username = "provenance-author-" + suffix;
        author.email = "provenance-author-" + suffix + "@example.com";
        author.password = "test-password-hash";
        author.status = 1;
        author.persist();

        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "provenance-post-" + suffix;
        post.title = Map.of("zh-cn", "预览来源文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = author;
        post.publishedAt = now;
        post.createdAt = now;
        post.updatedAt = now;
        post.extraInfo = Map.of("points_price", pointsPrice, "free_lines", freeLines);
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn", content);
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = author;
        revision.createdAt = now;
        revision.publicPreviewRendered = previewRendered;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        return post.slug;
    }
}
