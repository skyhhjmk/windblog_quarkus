package com.biliwind.blog.controller;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkMonitorSource;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.AdminEmailActionTokenService;
import com.biliwind.blog.service.AdminEmailActionTokenService.Action;
import com.biliwind.blog.service.AdminEmailActionTokenService.VerifiedAction;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.CommentEmailNotificationService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import com.biliwind.blog.service.PublicUrlService;
import com.biliwind.blog.service.UserNotificationService;
import com.biliwind.blog.service.UserProfileReviewService;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.link.LinkMonitorPolicy;
import com.biliwind.blog.service.link.LinkMonitorService;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Handles one-click moderation actions triggered from administrator notification emails.
 */
@Path("/admin/email-action")
public class AdminEmailActionController {

    @Inject
    AdminEmailActionTokenService tokenService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;
    @Inject
    PublicUrlService publicUrlService;
    @Inject
    SettingsReader settingsReader;
    @Inject
    UserProfileReviewService userProfileReviewService;
    @Inject
    UserNotificationService userNotificationService;
    @Inject
    CommentEmailNotificationService commentEmailNotificationService;
    @Inject
    CacheService cacheService;
    @Inject
    AuditService auditService;
    @Inject
    LinkMonitorService linkMonitorService;
    @Inject
    Event<PostSyncedEvent> postSyncedEvent;
    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @GET
    @Transactional
    @Produces("text/html; charset=utf-8")
    public Response execute(@QueryParam("token") String token) {
        String siteName = resolveSiteName();
        String homeUrl = publicUrlService.buildPath("/");

        VerifiedAction verified = tokenService.verify(token);
        if (verified == null) {
            return htmlResponse(Response.Status.BAD_REQUEST, siteName,
                    "链接无效或已过期",
                    "该一键审核链接无效或已超过有效期，请前往管理后台手动处理。",
                    false, homeUrl);
        }

        User admin = User.findById(verified.adminId());
        if (!tokenService.isActiveAdmin(admin)) {
            return htmlResponse(Response.Status.FORBIDDEN, siteName,
                    "无权执行操作",
                    "对应的管理员账号不存在或已失效，请前往管理后台处理。",
                    false, homeUrl);
        }

        return switch (verified.targetType()) {
            case COMMENT -> handleCommentAction(verified, admin, siteName, homeUrl);
            case USER_PROFILE_REVIEW -> handleProfileReviewAction(verified, admin, siteName, homeUrl);
            case POST -> handlePostAction(verified, admin, siteName, homeUrl);
            case LINK -> handleLinkAction(verified, admin, siteName, homeUrl);
        };
    }

    private Response handleCommentAction(VerifiedAction verified, User admin, String siteName, String homeUrl) {
        Comment comment = Comment.findById(verified.targetId());
        if (comment == null || comment.deletedAt != null) {
            return htmlResponse(Response.Status.NOT_FOUND, siteName,
                    "评论不存在", "该评论可能已被删除。", false, homeUrl);
        }

        short targetStatus = verified.action() == Action.APPROVE ? (short) 1 : (short) 2;
        short targetAuditStatus = verified.action() == Action.APPROVE ? (short) 2 : (short) 3;
        String postUrl = comment.post == null || comment.post.slug == null
                ? homeUrl
                : publicUrlService.buildPath("/post/" + comment.post.slug + "#comment-" + comment.id);

        if (comment.status == targetStatus) {
            return htmlResponse(Response.Status.OK, siteName,
                    "评论已处理",
                    "该评论当前已经是目标状态，无需重复操作。",
                    true, postUrl);
        }

        short previousStatus = comment.status;
        comment.status = targetStatus;
        comment.auditStatus = targetAuditStatus;
        comment.isReviewing = false;
        auditService.log("comment", String.valueOf(comment.id), "email_one_click_" + verified.action().name().toLowerCase(),
                Map.of("status", previousStatus), Map.of("status", comment.status), null, admin.id);

        if (targetStatus == 1 && previousStatus != 1) {
            commentEmailNotificationService.notifyReplyRecipient(comment);
            userNotificationService.notifyCommentReply(comment);
        }
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);

        String actionText = verified.action() == Action.APPROVE ? "评论已审核通过" : "评论已标记为垃圾评论";
        return htmlResponse(Response.Status.OK, siteName,
                actionText,
                "管理员 " + admin.username + " 已通过邮件一键操作完成处理。",
                true, postUrl);
    }

    private Response handleProfileReviewAction(VerifiedAction verified, User admin, String siteName, String homeUrl) {
        try {
            if (verified.action() == Action.APPROVE) {
                userProfileReviewService.approve(verified.targetId(), admin.id, "邮件一键审核通过");
                return htmlResponse(Response.Status.OK, siteName,
                        "资料变更已通过",
                        "已审核通过该用户的资料修改申请。",
                        true, homeUrl);
            }
            userProfileReviewService.reject(verified.targetId(), admin.id, "管理员邮件一键驳回");
            return htmlResponse(Response.Status.OK, siteName,
                    "资料变更已驳回",
                    "已驳回该用户的资料修改申请。",
                    true, homeUrl);
        } catch (NotFoundException ex) {
            return htmlResponse(Response.Status.NOT_FOUND, siteName,
                    "审核记录不存在", "未找到对应的用户资料审核记录。", false, homeUrl);
        } catch (BadRequestException ex) {
            return htmlResponse(Response.Status.OK, siteName,
                    "审核记录已处理",
                    ex.getMessage() == null ? "该审核记录已处于完成状态，无需重复操作。" : ex.getMessage(),
                    true, homeUrl);
        }
    }

    private Response handlePostAction(VerifiedAction verified, User admin, String siteName, String homeUrl) {
        Post post = Post.findById(verified.targetId());
        if (post == null || post.deletedAt != null) {
            return htmlResponse(Response.Status.NOT_FOUND, siteName,
                    "文章不存在", "未找到对应的投稿文章。", false, homeUrl);
        }
        String postTitle = LanguageHelper.resolveLocalizedValue(post.title, LanguageConstant.LANG_ZH_CN);
        String displayTitle = (postTitle == null || postTitle.isBlank()) ? post.slug : postTitle;
        String postUrl = publicUrlService.buildPath("/post/" + post.slug);

        if (post.status != PostStatus.PENDING_REVIEW) {
            return htmlResponse(Response.Status.OK, siteName,
                    "投稿已处理",
                    "文章《" + displayTitle + "》当前不再处于待审核状态，无需重复操作。",
                    true, post.status == PostStatus.PUBLISHED ? postUrl : homeUrl);
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (verified.action() == Action.APPROVE) {
            if (post.currentRevision == null) {
                return htmlResponse(Response.Status.BAD_REQUEST, siteName,
                        "无法发布文章", "文章缺少可发布的草稿版本。", false, homeUrl);
            }
            post.publishedRevision = post.currentRevision;
            post.status = PostStatus.PUBLISHED;
            if (post.publishedAt == null) {
                post.publishedAt = now;
            }
            post.reviewedAt = now;
            post.reviewedBy = admin;
            post.updatedAt = now;
            userNotificationService.notifyPostPublished(post);
            postSyncedEvent.fire(new PostSyncedEvent(post.id));
            auditService.log("post", String.valueOf(post.id), "email_one_click_approve",
                    null, Map.of("status", post.status.name()), null, admin.id);
            return htmlResponse(Response.Status.OK, siteName,
                    "投稿已审核通过并发布",
                    "文章《" + displayTitle + "》已公开发布。",
                    true, postUrl);
        }

        post.status = PostStatus.DRAFT;
        post.reviewedAt = now;
        post.reviewedBy = admin;
        post.reviewNote = "管理员邮件一键退回草稿";
        post.updatedAt = now;
        userNotificationService.notifyPostRejected(post);
        auditService.log("post", String.valueOf(post.id), "email_one_click_reject",
                null, Map.of("status", post.status.name()), null, admin.id);
        return htmlResponse(Response.Status.OK, siteName,
                "投稿已退回草稿",
                "已将文章《" + displayTitle + "》退回作者草稿箱。",
                true, homeUrl);
    }

    private Response handleLinkAction(VerifiedAction verified, User admin, String siteName, String homeUrl) {
        Link link = Link.findById(verified.targetId());
        if (link == null) {
            return htmlResponse(Response.Status.NOT_FOUND, siteName,
                    "友链申请不存在", "未找到对应的友情链接申请记录。", false, homeUrl);
        }

        boolean approve = verified.action() == Action.APPROVE;
        short targetAppStatus = approve ? (short) 1 : (short) 3;
        if (link.applicationStatus == targetAppStatus) {
            return htmlResponse(Response.Status.OK, siteName,
                    "友链申请已处理",
                    "站点“" + link.name + "”的申请已处于目标状态，无需重复操作。",
                    true, homeUrl);
        }

        link.applicationStatus = targetAppStatus;
        link.status = approve ? (short) 1 : (short) 2;
        link.updatedAt = OffsetDateTime.now();
        auditService.log("link", String.valueOf(link.id),
                approve ? "email_one_click_application_approved" : "email_one_click_application_rejected",
                null, Map.of("applicationStatus", link.applicationStatus), null, admin.id);
        if (approve && LinkMonitorPolicy.isMonitoringEnabled(link)) {
            linkMonitorService.enqueueCheck(link.id, LinkMonitorSource.AUTOMATIC);
        }
        dataSyncEvent.fire(new DataSyncEvent("LINK", link.id, "UPSERT"));

        return htmlResponse(Response.Status.OK, siteName,
                approve ? "友链申请已通过" : "友链申请已拒绝",
                "站点“" + link.name + "”已处理完成。",
                true, homeUrl);
    }

    private Response htmlResponse(Response.Status status, String siteName, String title,
                                  String message, boolean success, String backUrl) {
        String html = emailTemplateRenderer.renderAdminActionResultPage(siteName, title, message, success, backUrl);
        return Response.status(status).entity(html).build();
    }

    private String resolveSiteName() {
        if (settingsReader == null) {
            return "WindBlog";
        }
        String title = settingsReader.text(SettingKey.SITE_INFO, "title");
        return (title == null || title.isBlank()) ? "WindBlog" : title.trim();
    }
}
