package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserProfileReview;
import com.biliwind.blog.service.AdminEmailActionTokenService.Action;
import com.biliwind.blog.service.AdminEmailActionTokenService.TargetType;
import com.biliwind.blog.service.EmailTemplateRenderer.ActionButton;
import com.biliwind.blog.service.EmailTemplateRenderer.DetailRow;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends actionable email notifications to site administrators for new comments and pending reviews.
 */
@ApplicationScoped
public class AdminNotificationEmailService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminNotificationEmailService.class);

    @Inject
    EmailDeliveryService emailDeliveryService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;
    @Inject
    AdminEmailActionTokenService actionTokenService;
    @Inject
    PublicUrlService publicUrlService;
    @Inject
    SettingsReader settingsReader;

    @Transactional
    public void notifyNewComment(Comment comment) {
        if (comment == null || comment.id == null) {
            return;
        }
        List<User> admins = findAdminRecipients();
        if (admins.isEmpty()) {
            return;
        }

        String siteName = resolveSiteName();
        String postTitle = comment.post == null ? "未知文章" : resolvePostTitle(comment.post);
        String postUrl = comment.post == null || comment.post.slug == null
                ? publicUrlService.buildPath("/")
                : publicUrlService.buildPath("/post/" + comment.post.slug + "#comment-" + comment.id);
        String authorName = resolveCommentAuthor(comment);
        String statusLabel = comment.isReviewing ? "待审核（AI 审核中）" : switch (comment.status) {
            case 1 -> "已通过";
            case 2 -> "垃圾/已拒绝";
            default -> "待审核";
        };

        List<DetailRow> details = List.of(
                new DetailRow("所属文章", postTitle),
                new DetailRow("评论者", authorName),
                new DetailRow("当前状态", statusLabel)
        );
        String subject = "[" + siteName + "] 新评论通知：《" + postTitle + "》";

        for (User admin : admins) {
            List<ActionButton> actions = List.of(
                    new ActionButton("一键通过评论", buildActionUrl(TargetType.COMMENT, comment.id, Action.APPROVE, admin.id), "#16a34a"),
                    new ActionButton("一键标记垃圾", buildActionUrl(TargetType.COMMENT, comment.id, Action.REJECT, admin.id), "#dc2626")
            );
            String html = emailTemplateRenderer.renderAdminActionNotice(
                    siteName,
                    "收到一条新评论",
                    "文章《" + postTitle + "》收到新评论，您可以直接在下方点击按钮快速处理。",
                    details,
                    "评论内容",
                    comment.content,
                    actions,
                    "查看文章评论",
                    postUrl);
            queueSafe("ADMIN_COMMENT_NOTICE", admin.email, subject, html);
        }
    }

    @Transactional
    public void notifyUserProfileReviewPending(UserProfileReview review) {
        if (review == null || review.id == null || !"PENDING".equals(review.status)) {
            return;
        }
        List<User> admins = findAdminRecipients();
        if (admins.isEmpty()) {
            return;
        }

        User targetUser = review.userId == null ? null : User.findById(review.userId);
        String username = targetUser != null ? targetUser.username : ("用户 #" + review.userId);
        String siteName = resolveSiteName();
        StringBuilder changeSummary = new StringBuilder();
        changeSummary.append("昵称：").append(safeText(review.oldNickname)).append(" -> ").append(safeText(review.newNickname)).append("\n");
        changeSummary.append("简介：").append(safeText(review.oldBio)).append(" -> ").append(safeText(review.newBio));
        if (review.pendingAvatarPath != null && !review.pendingAvatarPath.isBlank()) {
            changeSummary.append("\n头像：已上传新头像等待审核");
        }
        if (review.aiReason != null && !review.aiReason.isBlank()) {
            changeSummary.append("\nAI 审核备注：").append(review.aiReason.trim());
        }

        List<DetailRow> details = List.of(
                new DetailRow("申请用户", username),
                new DetailRow("审核单号", "#" + review.id),
                new DetailRow("AI 状态", review.aiStatus == null ? "NOT_RUN" : review.aiStatus)
        );
        String subject = "[" + siteName + "] 待审核资料变更：" + username;

        for (User admin : admins) {
            List<ActionButton> actions = List.of(
                    new ActionButton("一键通过变更", buildActionUrl(TargetType.USER_PROFILE_REVIEW, review.id, Action.APPROVE, admin.id), "#16a34a"),
                    new ActionButton("一键驳回变更", buildActionUrl(TargetType.USER_PROFILE_REVIEW, review.id, Action.REJECT, admin.id), "#dc2626")
            );
            String html = emailTemplateRenderer.renderAdminActionNotice(
                    siteName,
                    "新的用户资料变更待审核",
                    "用户 " + username + " 提交了资料修改申请，需要管理员人工审核。",
                    details,
                    "变更详情",
                    changeSummary.toString(),
                    actions,
                    "站点首页",
                    publicUrlService.buildPath("/"));
            queueSafe("ADMIN_REVIEW_NOTICE", admin.email, subject, html);
        }
    }

    @Transactional
    public void notifyPostPendingReview(Post post) {
        if (post == null || post.id == null) {
            return;
        }
        List<User> admins = findAdminRecipients();
        if (admins.isEmpty()) {
            return;
        }

        String siteName = resolveSiteName();
        String postTitle = resolvePostTitle(post);
        String authorName = post.authorName != null && !post.authorName.isBlank()
                ? post.authorName.trim()
                : (post.user != null ? post.user.username : "投稿用户");
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, LanguageConstant.LANG_ZH_CN);
        List<DetailRow> details = List.of(
                new DetailRow("文章标题", postTitle),
                new DetailRow("投稿作者", authorName),
                new DetailRow("文章标识", post.slug)
        );
        String subject = "[" + siteName + "] 新文章投稿待审核：《" + postTitle + "》";

        for (User admin : admins) {
            List<ActionButton> actions = List.of(
                    new ActionButton("一键通过并发布", buildActionUrl(TargetType.POST, post.id, Action.APPROVE, admin.id), "#16a34a"),
                    new ActionButton("一键退回草稿", buildActionUrl(TargetType.POST, post.id, Action.REJECT, admin.id), "#dc2626")
            );
            String html = emailTemplateRenderer.renderAdminActionNotice(
                    siteName,
                    "新的投稿文章待审核",
                    "用户提交了一篇新文章《" + postTitle + "》，您可以直接一键通过发布或退回草稿。",
                    details,
                    "文章摘要",
                    summary,
                    actions,
                    "站点首页",
                    publicUrlService.buildPath("/"));
            queueSafe("ADMIN_REVIEW_NOTICE", admin.email, subject, html);
        }
    }

    @Transactional
    public void notifyFriendLinkPendingReview(Link link) {
        if (link == null || link.id == null) {
            return;
        }
        List<User> admins = findAdminRecipients();
        if (admins.isEmpty()) {
            return;
        }

        String siteName = resolveSiteName();
        List<DetailRow> details = List.of(
                new DetailRow("站点名称", link.name),
                new DetailRow("站点地址", link.url),
                new DetailRow("联系邮箱", link.email),
                new DetailRow("反链检测", link.backlinkStatus)
        );
        String subject = "[" + siteName + "] 新友链申请待审核：" + link.name;

        for (User admin : admins) {
            List<ActionButton> actions = List.of(
                    new ActionButton("一键通过友链", buildActionUrl(TargetType.LINK, link.id, Action.APPROVE, admin.id), "#16a34a"),
                    new ActionButton("一键拒绝申请", buildActionUrl(TargetType.LINK, link.id, Action.REJECT, admin.id), "#dc2626")
            );
            String html = emailTemplateRenderer.renderAdminActionNotice(
                    siteName,
                    "收到新的友链申请",
                    "站点“" + link.name + "”提交了友情链接申请，您可以直接点击下方按钮处理。",
                    details,
                    "站点描述与备注",
                    buildLinkPreview(link),
                    actions,
                    "访问申请站点",
                    link.url);
            queueSafe("ADMIN_REVIEW_NOTICE", admin.email, subject, html);
        }
    }

    List<User> findAdminRecipients() {
        List<User> candidates = User.list(
                "roleName in ?1 and status = 1 and deletedAt is null order by id asc",
                List.of(RoleConstant.SUPER_ADMIN, RoleConstant.ADMIN));
        Map<String, User> deduplicated = new LinkedHashMap<>();
        for (User candidate : candidates) {
            if (candidate == null || candidate.email == null || candidate.email.isBlank()) {
                continue;
            }
            deduplicated.putIfAbsent(candidate.email.trim().toLowerCase(Locale.ROOT), candidate);
        }
        return new ArrayList<>(deduplicated.values());
    }

    String buildActionUrl(TargetType targetType, long targetId, Action action, long adminId) {
        String token = actionTokenService.issue(targetType, targetId, action, adminId);
        return publicUrlService.buildPath("/admin/email-action?token=" + token);
    }

    private void queueSafe(String scenario, String recipientEmail, String subject, String html) {
        try {
            emailDeliveryService.queueRenderedHtml(scenario, recipientEmail, subject, html);
        } catch (Exception ex) {
            LOG.warn("Failed to queue admin notification email to {}: {}", recipientEmail, ex.getMessage());
        }
    }

    private String resolveSiteName() {
        if (settingsReader == null) {
            return "WindBlog";
        }
        String title = settingsReader.text(SettingKey.SITE_INFO, "title");
        return (title == null || title.isBlank()) ? "WindBlog" : title.trim();
    }

    private String resolvePostTitle(Post post) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, LanguageConstant.LANG_ZH_CN);
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        return post.slug == null ? "未命名文章" : post.slug;
    }

    private String resolveCommentAuthor(Comment comment) {
        if (comment.user != null) {
            if (comment.user.nickname != null && !comment.user.nickname.isBlank()) {
                return comment.user.nickname + " (@" + comment.user.username + ")";
            }
            return comment.user.username;
        }
        String name = comment.guestName != null && !comment.guestName.isBlank() ? comment.guestName : "访客";
        if (comment.guestEmail != null && !comment.guestEmail.isBlank()) {
            return name + " <" + comment.guestEmail + ">";
        }
        return name;
    }

    private String buildLinkPreview(Link link) {
        StringBuilder sb = new StringBuilder();
        if (link.description != null && !link.description.isBlank()) {
            sb.append("简介：").append(link.description.trim());
        }
        if (link.note != null && !link.note.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("备注：").append(link.note.trim());
        }
        return sb.toString();
    }

    private String safeText(String value) {
        return (value == null || value.isBlank()) ? "(空)" : value.trim();
    }
}
