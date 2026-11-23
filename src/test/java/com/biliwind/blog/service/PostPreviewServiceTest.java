package com.biliwind.blog.service;

import com.biliwind.blog.controller.api.admin.AdminAuthorizationPolicy;
import com.biliwind.blog.model.ArticleMode;
import com.biliwind.blog.model.PostRenderType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostPreviewServiceTest {

    @Test
    void shouldNormalizeTtlWithinSafeBounds() {
        assertEquals(PostPreviewService.DEFAULT_TTL_MINUTES, PostPreviewService.normalizeTtlMinutes(null));
        assertEquals(PostPreviewService.MIN_TTL_MINUTES, PostPreviewService.normalizeTtlMinutes(1));
        assertEquals(PostPreviewService.MAX_TTL_MINUTES, PostPreviewService.normalizeTtlMinutes(99999));
        assertEquals(120, PostPreviewService.normalizeTtlMinutes(120));
    }

    @Test
    void shouldStoreAndRetrieveValidSnapshotAndRejectExpiredOrInvalidTokens() {
        PostPreviewService service = new PostPreviewService();
        OffsetDateTime now = OffsetDateTime.now();

        PostPreviewService.PostPreviewSnapshot active = new PostPreviewService.PostPreviewSnapshot(
                null,
                "draft-slug",
                Map.of("zh-cn", "草稿标题"),
                Map.of("zh-cn", "摘要"),
                Map.of(),
                (short) 0,
                Map.of("zh-cn", "# Hello Preview"),
                PostRenderType.MARKDOWN,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                List.of(),
                "Author",
                now,
                now,
                (short) 0,
                0L,
                List.of(),
                List.of(),
                "ALL_RIGHTS_RESERVED",
                ArticleMode.STANDARD,
                null,
                null,
                "zh-cn",
                now,
                now.plusMinutes(30));

        String validToken = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-";
        service.storeSnapshot(validToken, active, Duration.ofMinutes(30));

        Optional<PostPreviewService.PostPreviewSnapshot> found = service.find(validToken);
        assertTrue(found.isPresent());
        assertEquals("draft-slug", found.get().slug());
        assertEquals("草稿标题", found.get().title().get("zh-cn"));

        PostPreviewService.PostPreviewSnapshot expired = new PostPreviewService.PostPreviewSnapshot(
                null,
                "expired-slug",
                Map.of("zh-cn", "过期标题"),
                Map.of(),
                Map.of(),
                (short) 0,
                Map.of("zh-cn", "expired"),
                PostRenderType.MARKDOWN,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                List.of(),
                "Author",
                now.minusHours(2),
                now.minusHours(2),
                (short) 0,
                0L,
                List.of(),
                List.of(),
                "ALL_RIGHTS_RESERVED",
                ArticleMode.STANDARD,
                null,
                null,
                "zh-cn",
                now.minusHours(2),
                now.minusMinutes(1));

        String expiredToken = "ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210_-";
        service.storeSnapshot(expiredToken, expired, Duration.ofMinutes(1));
        assertTrue(service.find(expiredToken).isEmpty());

        assertTrue(service.find(null).isEmpty());
        assertTrue(service.find("short").isEmpty());
        assertTrue(service.find("../etc/passwd123456").isEmpty());
    }

    @Test
    void shouldAuthorizePreviewLinkEndpointAsDailyPostWriteAction() {
        AdminAuthorizationPolicy.Decision decision =
                AdminAuthorizationPolicy.decide("POST", "/api/admin/posts/preview-links");
        assertEquals("post", decision.resource());
        assertEquals("post.write", decision.action());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.DAILY, decision.riskLevel());
        assertFalse(decision.superAdminOnly());
        assertFalse(decision.stepUpRequired());
        assertFalse(decision.idempotencyRequired());
    }
}
