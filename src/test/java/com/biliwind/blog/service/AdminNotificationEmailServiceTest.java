package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserProfileReview;
import com.biliwind.blog.service.AdminEmailActionTokenService.Action;
import com.biliwind.blog.service.AdminEmailActionTokenService.TargetType;
import com.biliwind.blog.service.AdminEmailActionTokenService.VerifiedAction;
import com.biliwind.blog.service.EmailTemplateRenderer.ActionButton;
import com.biliwind.blog.service.EmailTemplateRenderer.DetailRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminNotificationEmailServiceTest {

    private AdminEmailActionTokenService tokenService;
    private EmailTemplateRenderer templateRenderer;
    private AdminNotificationEmailService notificationService;

    @BeforeEach
    void setUp() {
        tokenService = new AdminEmailActionTokenService();
        tokenService.signingSecret = "unit-test-secret-key-for-admin-email-actions";

        PublicUrlService publicUrlService = new PublicUrlService();
        publicUrlService.configuredPublicUrl = "https://blog.example.com";

        templateRenderer = new EmailTemplateRenderer();

        notificationService = new AdminNotificationEmailService();
        notificationService.actionTokenService = tokenService;
        notificationService.publicUrlService = publicUrlService;
        notificationService.emailTemplateRenderer = templateRenderer;
    }

    @Test
    void shouldIssueVerifyAndRejectTamperedOrExpiredTokens() {
        String token = tokenService.issue(TargetType.COMMENT, 42L, Action.APPROVE, 7L);
        VerifiedAction verified = tokenService.verify(token);
        assertNotNull(verified);
        assertEquals(TargetType.COMMENT, verified.targetType());
        assertEquals(42L, verified.targetId());
        assertEquals(Action.APPROVE, verified.action());
        assertEquals(7L, verified.adminId());

        // Tampered token must fail
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertNull(tokenService.verify(tampered));

        // Expired token must fail
        String expired = tokenService.issueWithExpiresAt(
                TargetType.POST, 10L, Action.REJECT, 7L, Instant.now().minusSeconds(5));
        assertNull(tokenService.verify(expired));

        // Active admin check
        User activeAdmin = new User();
        activeAdmin.status = 1;
        activeAdmin.roleName = RoleConstant.ADMIN;
        assertTrue(tokenService.isActiveAdmin(activeAdmin));

        User normalUser = new User();
        normalUser.status = 1;
        normalUser.roleName = RoleConstant.USER;
        assertFalse(tokenService.isActiveAdmin(normalUser));
    }

    @Test
    void shouldRenderOneClickButtonsAndPlainTextFallbackUrls() {
        String approveUrl = notificationService.buildActionUrl(TargetType.COMMENT, 99L, Action.APPROVE, 1L);
        String rejectUrl = notificationService.buildActionUrl(TargetType.COMMENT, 99L, Action.REJECT, 1L);
        String postUrl = "https://blog.example.com/post/hello-world#comment-99";

        String html = templateRenderer.renderAdminActionNotice(
                "WindBlog",
                "收到一条新评论",
                "文章《Hello World》收到新评论。",
                List.of(new DetailRow("评论者", "张三"), new DetailRow("当前状态", "待审核")),
                "评论内容",
                "写得很好！",
                List.of(
                        new ActionButton("一键通过评论", approveUrl, "#16a34a"),
                        new ActionButton("一键标记垃圾", rejectUrl, "#dc2626")),
                "查看文章评论",
                postUrl);

        assertTrue(html.contains("一键通过评论"));
        assertTrue(html.contains("一键标记垃圾"));
        assertTrue(html.contains("备用操作链接"));
        assertTrue(html.contains(approveUrl));
        assertTrue(html.contains(rejectUrl));
        assertTrue(html.contains(postUrl));

        // Plain-text MIME fallback must preserve every one-click URL
        String plainText = EmailDeliveryService.htmlToPlainText(html);
        assertTrue(plainText.contains("收到一条新评论"));
        assertTrue(plainText.contains("写得很好！"));
        assertTrue(plainText.contains(approveUrl));
        assertTrue(plainText.contains(rejectUrl));
        assertTrue(plainText.contains(postUrl));
        assertFalse(plainText.contains("<a "));
        assertFalse(plainText.contains("<table"));
    }

    @Test
    void shouldHandleNullOrMissingIdsGracefully() {
        Comment commentWithoutId = new Comment();
        notificationService.notifyNewComment(commentWithoutId);

        UserProfileReview reviewNotPending = new UserProfileReview();
        reviewNotPending.id = 1L;
        reviewNotPending.status = "APPROVED";
        notificationService.notifyUserProfileReviewPending(reviewNotPending);

        Post postWithoutId = new Post();
        postWithoutId.title = Map.of("zh-cn", "测试");
        notificationService.notifyPostPendingReview(postWithoutId);

        Link linkWithoutId = new Link();
        linkWithoutId.createdAt = OffsetDateTime.now();
        notificationService.notifyFriendLinkPendingReview(linkWithoutId);
    }
}
