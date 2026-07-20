package com.biliwind.blog.service;

import com.biliwind.blog.model.Comment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CommentEmailNotificationService {
    @Inject
    EmailDeliveryService emailDeliveryService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    public void notifyReplyRecipient(Comment replyComment) {
        if (replyComment.parent == null || replyComment.parent.user == null) return;
        if (replyComment.user != null && replyComment.parent.user.id.equals(replyComment.user.id)) return;
        String subject = "您的评论收到了回复";
        String html = emailTemplateRenderer.render("您的评论收到了回复", "您好：", replyComment.user.username
                + " 回复了您：\n" + replyComment.content, "查看文章", "/post/" + replyComment.post.slug);
        emailDeliveryService.queue("COMMENT_REPLY", replyComment.parent.user.email, subject, html);
    }

    private String escape(String content) {
        if (content == null) return "";
        return content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
