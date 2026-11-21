package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.EmailDispatchRequest;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ArticleEmailNotificationService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Inject
    EmailDeliveryService emailDeliveryService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;
    @Inject
    PublicUrlService publicUrlService;
    @Inject
    PublicContentSanitizer publicContentSanitizer;
    @Inject
    SettingsReader settingsReader;

    public void queueArticleUpdate(Post post, EmailDispatchRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.sendArticleUpdate()) || post.visibility != 0) {
            return;
        }
        if (request.channelId() != null && request.channelGroupId() != null) {
            throw new IllegalArgumentException("文章更新邮件只能指定通道或通道组");
        }
        List<User> recipients = User.list(
                "emailVerifiedAt is not null and subscribeArticleUpdates = true and status = 1 and deletedAt is null");
        if (recipients.isEmpty()) {
            return;
        }

        String lang = LanguageHelper.resolveLocalizedLanguage(post.title, LanguageConstant.LANG_ZH_CN);
        String title = resolveTitle(post.title, lang);
        String html = renderArticleEmail(post, lang, title);

        for (User recipient : recipients) {
            emailDeliveryService.queueRenderedHtmlWithRoute(
                    "ARTICLE_UPDATE",
                    recipient.email,
                    "新文章：" + title,
                    html,
                    request.channelGroupId(),
                    request.channelId());
        }
    }

    String renderArticleEmail(Post post, String lang, String title) {
        String summary = resolveSummary(post, lang);
        String metaLine = buildMetaLine(post);
        String articleUrl = publicUrlService.buildPath("/post/" + post.slug);
        String articleHtml = renderArticleBodyHtml(post, lang, title);
        String siteName = resolveSiteName();

        return emailTemplateRenderer.renderArticle(
                siteName, title, summary, metaLine, articleHtml, articleUrl);
    }

    String renderArticleBodyHtml(Post post, String lang, String title) {
        PostRevision revision = post.publishedRevision != null ? post.publishedRevision : post.currentRevision;
        String rawContent = revision == null ? null : LanguageHelper.resolveLocalizedValue(revision.contentMarkdown, lang);
        rawContent = MarkdownHelper.stripLegacyRenderedMarker(rawContent);
        if (rawContent == null || rawContent.isBlank()) {
            return "<p style=\"margin:0 0 16px;line-height:1.75\">新文章《"
                    + escapeHtml(title)
                    + "》已经发布，欢迎点击下方按钮阅读全文。</p>";
        }

        PostRenderType effectiveType = post.renderType == null ? PostRenderType.MARKDOWN : post.renderType;
        String rawHtml = switch (effectiveType) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS -> MarkdownHelper.toHtml(rawContent);
            case VDITOR, HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL, TUTORIAL_BLOCK -> rawContent;
        };
        String sanitized = publicContentSanitizer.sanitize(rawHtml);
        return prepareEmailArticleHtml(sanitized, publicUrlService.getBaseUrl());
    }

    static String prepareEmailArticleHtml(String sanitizedHtml, String baseUrl) {
        if (sanitizedHtml == null || sanitizedHtml.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(sanitizedHtml);
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));

        String normalizedBaseUrl = PublicUrlService.normalize(baseUrl);
        for (Element anchor : document.select("a[href]")) {
            String resolved = resolveAbsoluteUrl(anchor.attr("href"), normalizedBaseUrl);
            if (resolved != null) {
                anchor.attr("href", resolved);
            }
            appendInlineStyle(anchor, "color:#2563eb;text-decoration:underline;");
        }

        for (Element image : document.select("img[src]")) {
            String resolved = resolveAbsoluteUrl(image.attr("src"), normalizedBaseUrl);
            if (resolved != null) {
                image.attr("src", resolved);
            }
            image.removeAttr("loading");
            image.removeAttr("decoding");
            appendInlineStyle(image, "max-width:100%;height:auto;border-radius:8px;display:block;margin:16px auto;");
        }

        applyTagStyle(document, "p", "margin:0 0 16px;line-height:1.75;");
        applyTagStyle(document, "h1", "margin:28px 0 14px;font-size:22px;line-height:1.35;font-weight:700;color:#111827;");
        applyTagStyle(document, "h2", "margin:26px 0 12px;font-size:20px;line-height:1.35;font-weight:700;color:#111827;padding-bottom:6px;border-bottom:1px solid #e5e7eb;");
        applyTagStyle(document, "h3", "margin:22px 0 10px;font-size:18px;line-height:1.4;font-weight:600;color:#111827;");
        applyTagStyle(document, "h4, h5, h6", "margin:18px 0 8px;font-size:16px;line-height:1.4;font-weight:600;color:#1f2937;");
        applyTagStyle(document, "blockquote", "margin:16px 0;padding:12px 16px;border-left:4px solid #cbd5e1;background:#f8fafc;color:#475569;border-radius:0 6px 6px 0;");
        applyTagStyle(document, "pre", "margin:16px 0;padding:14px 16px;background:#0f172a;color:#e2e8f0;border-radius:8px;overflow-x:auto;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:13px;line-height:1.6;");
        for (Element code : document.select("code")) {
            Element parent = code.parent();
            if (parent != null && "pre".equalsIgnoreCase(parent.tagName())) {
                appendInlineStyle(code, "background:transparent;color:inherit;padding:0;font-family:inherit;font-size:inherit;");
            } else {
                appendInlineStyle(code, "padding:2px 6px;background:#f1f5f9;color:#0f172a;border-radius:4px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:0.9em;");
            }
        }
        applyTagStyle(document, "ul, ol", "margin:0 0 16px;padding-left:24px;line-height:1.75;");
        applyTagStyle(document, "li", "margin:4px 0;");
        applyTagStyle(document, "table", "width:100%;border-collapse:collapse;margin:16px 0;font-size:14px;");
        applyTagStyle(document, "th", "border:1px solid #e2e8f0;padding:8px 12px;text-align:left;background:#f8fafc;font-weight:600;color:#111827;");
        applyTagStyle(document, "td", "border:1px solid #e2e8f0;padding:8px 12px;text-align:left;");
        applyTagStyle(document, "hr", "border:none;border-top:1px solid #e5e7eb;margin:24px 0;");
        applyTagStyle(document, "div.callout, div.md-callout", "margin:16px 0;padding:12px 16px;background:#eff6ff;border-left:4px solid #3b82f6;border-radius:0 8px 8px 0;");

        return document.body().html();
    }

    private static void applyTagStyle(Document document, String selector, String style) {
        for (Element element : document.select(selector)) {
            appendInlineStyle(element, style);
        }
    }

    private static void appendInlineStyle(Element element, String style) {
        String existing = element.attr("style");
        if (existing == null || existing.isBlank()) {
            element.attr("style", style);
            return;
        }
        String trimmed = existing.trim();
        if (!trimmed.endsWith(";")) {
            trimmed = trimmed + ";";
        }
        element.attr("style", trimmed + style);
    }

    private static String resolveAbsoluteUrl(String rawUrl, String baseUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            return baseUrl + trimmed;
        }
        return trimmed;
    }

    private String resolveTitle(Map<String, String> titleMap, String lang) {
        String resolved = LanguageHelper.resolveLocalizedValue(titleMap, lang);
        return (resolved == null || resolved.isBlank()) ? "新文章" : resolved.trim();
    }

    private String resolveSummary(Post post, String lang) {
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        return LanguageHelper.resolveLocalizedValue(post.aiSummary, lang);
    }

    private String buildMetaLine(Post post) {
        String author = post.authorName;
        if ((author == null || author.isBlank()) && post.user != null) {
            author = (post.user.nickname != null && !post.user.nickname.isBlank())
                    ? post.user.nickname
                    : post.user.username;
        }
        OffsetDateTime timestamp = post.publishedAt != null ? post.publishedAt : post.updatedAt;
        StringBuilder sb = new StringBuilder();
        if (author != null && !author.isBlank()) {
            sb.append("作者：").append(author.trim());
        }
        if (timestamp != null) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(DATE_FORMATTER.format(timestamp));
        }
        return sb.toString();
    }

    private String resolveSiteName() {
        if (settingsReader == null) {
            return "WindBlog";
        }
        String title = settingsReader.text(SettingKey.SITE_INFO, "title");
        return (title == null || title.isBlank()) ? "WindBlog" : title.trim();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
