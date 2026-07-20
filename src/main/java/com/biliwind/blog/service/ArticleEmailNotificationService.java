package com.biliwind.blog.service;

import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.EmailDispatchRequest;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ArticleEmailNotificationService {
    @Inject
    EmailDeliveryService emailDeliveryService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    public void queueArticleUpdate(Post post, EmailDispatchRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.sendArticleUpdate()) || post.visibility != 0) return;
        if (request.channelId() != null && request.channelGroupId() != null)
            throw new IllegalArgumentException("文章更新邮件只能指定通道或通道组");
        List<User> recipients = User.list("emailVerifiedAt is not null and subscribeArticleUpdates = true and status = 1 and deletedAt is null");
        String title = resolveTitle(post.title);
        String articleUrl = "/post/" + post.slug;
        String html = emailTemplateRenderer.render("新文章发布", "您好：", "新文章《" + title + "》已经发布。", "阅读文章", articleUrl);
        for (User recipient : recipients) {
            emailDeliveryService.queueWithRoute("ARTICLE_UPDATE", recipient.email, "新文章：" + title, html,
                    request.channelGroupId(), request.channelId());
        }
    }

    private String resolveTitle(Map<String, String> titleMap) {
        if (titleMap == null || titleMap.isEmpty()) return "新文章";
        String chineseTitle = titleMap.get("zh-CN");
        if (chineseTitle != null && !chineseTitle.isBlank()) return chineseTitle;
        return titleMap.values().iterator().next();
    }
}
