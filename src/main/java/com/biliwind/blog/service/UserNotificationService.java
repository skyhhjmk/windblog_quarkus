package com.biliwind.blog.service;

import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserNotification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** 统一创建用户站内通知，避免各业务自行拼装通知数据。 */
@ApplicationScoped
public class UserNotificationService {

    @Transactional
    public UserNotification create(User user, String type, String title, String body, String targetUrl) {
        UserNotification notification = new UserNotification();
        notification.user = user;
        notification.type = type;
        notification.title = title;
        notification.body = body;
        notification.targetUrl = targetUrl;
        notification.persist();
        return notification;
    }

    @Transactional
    public void notifyPostSubmitted(Post post) {
        if (post != null && post.user != null) {
            create(post.user, "POST_SUBMITTED", "文章已提交审核",
                    "你的文章已进入审核队列，审核完成后会在这里通知你。",
                    "/user/center#user-post-editor");
        }
    }

    @Transactional
    public void notifyPostPublished(Post post) {
        if (post != null && post.user != null) {
            create(post.user, "POST_PUBLISHED", "文章已发布",
                    "你的文章《" + displayTitle(post) + "》已经通过审核并公开发布。",
                    "/post/" + post.slug);
        }
    }

    @Transactional
    public void notifyPostRejected(Post post) {
        if (post != null && post.user != null) {
            String note = post.reviewNote == null || post.reviewNote.isBlank()
                    ? "请修改后重新提交。"
                    : "审核意见：" + post.reviewNote.trim();
            create(post.user, "POST_REJECTED", "文章需要修改",
                    note, "/user/center#user-post-editor");
        }
    }

    @Transactional
    public void notifyCommentReply(Comment comment) {
        if (comment == null || comment.parent == null || comment.parent.user == null
                || comment.parent.user.id.equals(comment.user == null ? null : comment.user.id)) {
            return;
        }
        String target = comment.post == null ? "/user/center#user-notifications"
                : "/post/" + comment.post.slug + "#comment-" + comment.id;
        create(comment.parent.user, "COMMENT_REPLY", "你收到一条评论回复",
                "有人回复了你的评论。", target);
    }

    private String displayTitle(Post post) {
        if (post.title != null) {
            String title = post.title.get("zh-CN");
            if (title == null) {
                title = post.title.get("zh-cn");
            }
            if (title == null && !post.title.isEmpty()) {
                title = post.title.values().iterator().next();
            }
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return post.slug;
    }
}
