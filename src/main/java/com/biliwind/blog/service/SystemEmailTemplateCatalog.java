package com.biliwind.blog.service;

import com.biliwind.blog.service.EmailTemplateRenderer.ActionButton;
import com.biliwind.blog.service.EmailTemplateRenderer.DetailRow;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.List;

/**
 * Provides read-only metadata and rendered HTML previews for all built-in system email templates.
 */
@ApplicationScoped
public class SystemEmailTemplateCatalog {

    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    SettingsReader settingsReader;

    @RegisterForReflection
    public record SystemEmailTemplateItem(
            String templateKey,
            String name,
            String category,
            String description,
            String subjectTemplate,
            String title,
            String greeting,
            String content,
            String buttonText,
            String buttonUrl,
            List<String> variables,
            boolean system,
            boolean editable) {
    }

    @RegisterForReflection
    public record EmailTemplatePreviewResponse(
            String templateKey,
            String name,
            String subject,
            String html) {
    }

    public List<SystemEmailTemplateItem> listAll() {
        return List.of(
                new SystemEmailTemplateItem(
                        "REGISTRATION_VERIFICATION",
                        "注册邮箱验证",
                        "用户认证",
                        "新用户注册或重新发送验证邮件时触发，用于验证注册邮箱归属。",
                        "请验证您的邮箱",
                        "验证您的邮箱",
                        "您好 {username}：",
                        "请点击下方按钮完成邮箱验证。",
                        "验证邮箱",
                        "{verify_url}",
                        List.of("username", "verify_url"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "PASSWORD_RESET",
                        "重置密码",
                        "用户认证",
                        "用户在前台申请找回密码时触发，发送一次性密码重置链接。",
                        "重置 WindBlog 密码",
                        "重置您的密码",
                        "您好 {username}：",
                        "请点击下方按钮重置密码，该链接在有效期内仅可使用一次。",
                        "重置密码",
                        "{reset_url}",
                        List.of("username", "reset_url"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "COMMENT_REPLY",
                        "评论回复通知",
                        "用户互动",
                        "当一条回复评论审核通过后触发，通知被回复的原评论作者。",
                        "您的评论收到了回复",
                        "您的评论收到了回复",
                        "您好：",
                        "{replier_username} 回复了您：\n{reply_content}",
                        "查看文章",
                        "/post/{post_slug}",
                        List.of("replier_username", "reply_content", "post_slug"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "ARTICLE_UPDATE",
                        "新文章更新通知",
                        "内容订阅",
                        "管理员发布公开文章并勾选发送文章更新邮件时触发，向已订阅用户推送富文本文章内容。",
                        "新文章：{article_title}",
                        "{article_title}",
                        "（文章富文本布局，无单独称呼行）",
                        "{article_summary}\n\n{article_html}",
                        "在浏览器中阅读原文",
                        "{article_url}",
                        List.of("site_name", "article_title", "article_summary", "article_meta", "article_html", "article_url"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "FRIEND_LINK_MONITOR",
                        "友链检测异常提醒",
                        "友链通知",
                        "友链巡检连续异常进入缓冲期或超时自动隐藏时触发，通知对方站长。",
                        "WindBlog 友链检测异常提醒 / WindBlog 友链已自动隐藏",
                        "友链检测异常提醒",
                        "您好，友链站长：",
                        "友链名称：{link_name}\n友链地址：{link_url}\n异常状态：{condition_label}\n{status_description}",
                        "访问本站",
                        "{site_url}",
                        List.of("link_name", "link_url", "condition_label", "status_description", "site_url"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "FRIEND_LINK_SITE_UPDATE",
                        "友链本站信息变更通知",
                        "友链通知",
                        "管理员在友链管理中手动发送本站信息变更通知（如域名迁移、站点名称或图标变更）时触发。",
                        "[{site_name}] 友链信息变更通知",
                        "{site_name} 站点信息变更通知",
                        "尊敬的「{link_name}」站长：",
                        "自您添加我站友链以来，我站信息已经变更，这是最新信息：\n\n站点名称：{site_name}\n站点链接：{site_url}\n站点描述：{site_description}\n站点图标：{site_icon}\n\n以下是附加备注：\n{admin_note}\n\n如您方便，欢迎在贵站友链页面更新我站信息，感谢您的支持！",
                        "访问本站",
                        "{site_url}",
                        List.of("link_name", "site_name", "site_subtitle", "site_url", "site_description", "site_icon", "admin_note"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "ADMIN_COMMENT_NOTICE",
                        "管理员新评论审核通知",
                        "管理员通知",
                        "前台收到新评论时发送给管理员，附带一键通过/一键标记垃圾按钮。",
                        "[{site_name}] 新评论通知：《{post_title}》",
                        "收到一条新评论",
                        "（管理员快捷审核布局）",
                        "文章《{post_title}》收到新评论，您可以直接在下方点击按钮快速处理。\n\n评论者：{comment_author}\n评论内容：{comment_content}",
                        "一键通过评论 / 一键标记垃圾",
                        "{action_url}",
                        List.of("site_name", "post_title", "comment_author", "comment_status", "comment_content", "action_url"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "ADMIN_REVIEW_NOTICE",
                        "管理员待审核事项通知",
                        "管理员通知",
                        "收到新的用户资料变更、文章投稿或友链申请时发送给管理员，附带一键审核按钮。",
                        "[{site_name}] 待审核通知：{target_title}",
                        "新的待审核事项",
                        "（管理员快捷审核布局）",
                        "系统收到新的待审核申请（用户资料变更 / 文章投稿 / 友链申请），您可以直接点击邮件按钮一键通过或驳回。",
                        "一键通过 / 一键驳回",
                        "{action_url}",
                        List.of("site_name", "target_title", "applicant", "summary", "action_url"),
                        true,
                        false),
                new SystemEmailTemplateItem(
                        "CHANNEL_TEST",
                        "SMTP 通道测试",
                        "系统测试",
                        "管理员在邮件中心测试 SMTP 通道连通性时发送。",
                        "WindBlog SMTP 通道测试",
                        "SMTP 通道测试",
                        "您好：",
                        "这是一封用于验证 SMTP 通道连通性的测试邮件。",
                        "返回管理后台",
                        "/",
                        List.of(),
                        true,
                        false)
        );
    }

    public SystemEmailTemplateItem findByKey(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            throw new NotFoundException("系统邮件模板不存在");
        }
        String normalized = templateKey.trim();
        for (SystemEmailTemplateItem item : listAll()) {
            if (item.templateKey().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new NotFoundException("系统邮件模板不存在：" + templateKey);
    }

    public EmailTemplatePreviewResponse renderPreview(String templateKey) {
        SystemEmailTemplateItem item = findByKey(templateKey);
        String siteName = resolveSiteName();
        String baseUrl = publicUrlService != null ? publicUrlService.getBaseUrl() : "https://blog.example.com";

        return switch (item.templateKey()) {
            case "ARTICLE_UPDATE" -> new EmailTemplatePreviewResponse(
                    item.templateKey(),
                    item.name(),
                    "新文章：WindBlog 架构实践指南",
                    emailTemplateRenderer.renderArticle(
                            siteName,
                            "WindBlog 架构实践指南",
                            "这是一段用于预览新文章更新邮件效果的示例摘要。",
                            "作者：管理员 · 2026-11-24 00:00",
                            "<p style=\"margin:0 0 16px;line-height:1.75;\">欢迎阅读本期更新文章，这里会展示经过安全清洗与内联样式优化的正文内容。</p>",
                            baseUrl + "/post/windblog-architecture-guide"));
            case "ADMIN_COMMENT_NOTICE" -> new EmailTemplatePreviewResponse(
                    item.templateKey(),
                    item.name(),
                    "[" + siteName + "] 新评论通知：《示例文章》",
                    emailTemplateRenderer.renderAdminActionNotice(
                            siteName,
                            "收到一条新评论",
                            "文章《示例文章》收到新评论，您可以直接在下方点击按钮快速处理。",
                            List.of(
                                    new DetailRow("所属文章", "示例文章"),
                                    new DetailRow("评论者", "访客 Alice"),
                                    new DetailRow("当前状态", "待审核")),
                            "评论内容",
                            "这篇文章写得非常清晰，感谢分享！",
                            List.of(
                                    new ActionButton("一键通过评论", baseUrl + "/admin/email-action?token=sample-approve", "#16a34a"),
                                    new ActionButton("一键标记垃圾", baseUrl + "/admin/email-action?token=sample-reject", "#dc2626")),
                            "查看文章评论",
                            baseUrl + "/post/sample-post"));
            case "ADMIN_REVIEW_NOTICE" -> new EmailTemplatePreviewResponse(
                    item.templateKey(),
                    item.name(),
                    "[" + siteName + "] 新友链申请待审核：示例站点",
                    emailTemplateRenderer.renderAdminActionNotice(
                            siteName,
                            "收到新的友链申请",
                            "站点“示例站点”提交了友情链接申请，您可以直接点击下方按钮处理。",
                            List.of(
                                    new DetailRow("站点名称", "示例站点"),
                                    new DetailRow("站点地址", "https://friend.example.com"),
                                    new DetailRow("联系邮箱", "owner@friend.example.com")),
                            "站点描述与备注",
                            "简介：记录技术与生活。",
                            List.of(
                                    new ActionButton("一键通过友链", baseUrl + "/admin/email-action?token=sample-approve", "#16a34a"),
                                    new ActionButton("一键拒绝申请", baseUrl + "/admin/email-action?token=sample-reject", "#dc2626")),
                            "访问申请站点",
                            "https://friend.example.com"));
            case "FRIEND_LINK_SITE_UPDATE" -> new EmailTemplatePreviewResponse(
                    item.templateKey(),
                    item.name(),
                    "[" + siteName + "] 友链信息变更通知",
                    emailTemplateRenderer.render(
                            siteName + " 站点信息变更通知",
                            "尊敬的「示例友链」站长：",
                            "自您添加我站友链以来，我站信息已经变更，这是最新信息：\n\n"
                                    + "站点名称：" + siteName + "\n"
                                    + "站点链接：" + baseUrl + "\n"
                                    + "站点描述：" + resolveSiteDescription() + "\n\n"
                                    + "以下是附加备注：\n本站已从原域名 old.example.com 迁移到 " + baseUrl + "，欢迎更新友链信息。\n\n"
                                    + "如您方便，欢迎在贵站友链页面更新我站信息，感谢您的支持！",
                            "访问本站",
                            baseUrl));
            default -> {
                String sampleSubject = item.subjectTemplate()
                        .replace("{site_name}", siteName);
                String sampleGreeting = item.greeting()
                        .replace("{username}", "Alice")
                        .replace("{link_name}", "示例友链");
                String sampleContent = item.content()
                        .replace("{replier_username}", "Bob")
                        .replace("{reply_content}", "感谢回复，这条建议很有帮助！")
                        .replace("{link_name}", "示例友链")
                        .replace("{link_url}", "https://friend.example.com")
                        .replace("{condition_label}", "未检测到反链")
                        .replace("{status_description}", "连续 3 次检测到此问题，友链将在缓冲期内继续展示。");
                String sampleButtonUrl = item.buttonUrl()
                        .replace("{verify_url}", baseUrl + "/verify-email?token=sample")
                        .replace("{reset_url}", baseUrl + "/reset-password?token=sample")
                        .replace("{post_slug}", "sample-post")
                        .replace("{site_url}", baseUrl);
                yield new EmailTemplatePreviewResponse(
                        item.templateKey(),
                        item.name(),
                        sampleSubject,
                        emailTemplateRenderer.render(
                                item.title(),
                                sampleGreeting,
                                sampleContent,
                                item.buttonText(),
                                sampleButtonUrl));
            }
        };
    }

    private String resolveSiteName() {
        if (settingsReader == null) {
            return "WindBlog";
        }
        String title = settingsReader.text(SettingKey.SITE_INFO, "title");
        return (title == null || title.isBlank()) ? "WindBlog" : title.trim();
    }

    private String resolveSiteDescription() {
        if (settingsReader == null) {
            return "极简技术博客";
        }
        String desc = settingsReader.text(SettingKey.SITE_INFO, "description");
        return (desc == null || desc.isBlank()) ? "极简技术博客" : desc.trim();
    }
}
