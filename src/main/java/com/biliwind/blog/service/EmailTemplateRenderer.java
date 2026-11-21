package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class EmailTemplateRenderer {

    public record DetailRow(String label, String value) {
    }

    public record ActionButton(String label, String url, String backgroundHex) {
    }
    public String render(String title, String greeting, String content, String buttonText, String buttonUrl) {
        String escapedTitle = escape(title);
        String escapedGreeting = escape(greeting);
        String escapedContent = escape(content).replace("\n", "<br>");
        String escapedButtonText = escape(buttonText);
        String safeButtonUrl = escapeAttribute(buttonUrl);
        return "<!doctype html><html><body style=\"margin:0;background:#f4f7fb;font-family:Arial,sans-serif;color:#1f2937\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td align=\"center\" style=\"padding:32px 16px\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:600px;background:#ffffff;border-radius:16px;overflow:hidden\">"
                + "<tr><td style=\"padding:28px 32px;background:#1d4ed8;color:#ffffff\"><strong>WindBlog</strong></td></tr>"
                + "<tr><td style=\"padding:32px\"><h1 style=\"margin:0 0 20px;font-size:24px\">" + escapedTitle + "</h1>"
                + "<p>" + escapedGreeting + "</p><p style=\"line-height:1.7\">" + escapedContent + "</p>"
                + "<p style=\"margin:28px 0\"><a href=\"" + safeButtonUrl + "\" style=\"display:inline-block;padding:12px 20px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:8px\">" + escapedButtonText + "</a></p>"
                + "</td></tr><tr><td style=\"padding:20px 32px;background:#f8fafc;color:#64748b;font-size:12px\">此邮件由 WindBlog 自动发送，请勿直接回复。</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /**
     * Wraps a sanitized, inline-styled article HTML body in a responsive rich-text email layout.
     */
    public String renderArticle(String siteName,
                                String articleTitle,
                                String summary,
                                String metaLine,
                                String articleHtml,
                                String articleUrl) {
        String resolvedSiteName = (siteName == null || siteName.isBlank()) ? "WindBlog" : siteName.trim();
        String escapedSiteName = escape(resolvedSiteName);
        String escapedTitle = escape(articleTitle);
        String safeArticleUrl = escapeAttribute(articleUrl);

        StringBuilder metaAndSummary = new StringBuilder();
        if (metaLine != null && !metaLine.isBlank()) {
            metaAndSummary.append("<p style=\"margin:0 0 18px;font-size:13px;color:#6b7280;line-height:1.5\">")
                    .append(escape(metaLine))
                    .append("</p>");
        }
        if (summary != null && !summary.isBlank()) {
            metaAndSummary.append("<div style=\"margin:0 0 24px;padding:14px 18px;background:#f8fafc;border-left:4px solid #2563eb;border-radius:0 8px 8px 0;color:#475569;font-size:15px;line-height:1.65\">")
                    .append(escape(summary.trim()).replace("\n", "<br>"))
                    .append("</div>");
        }

        String safeBody = articleHtml == null ? "" : articleHtml;

        return "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',Arial,sans-serif;color:#1f2937\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#f4f7fb\"><tr><td align=\"center\" style=\"padding:32px 16px\">"
                + "<table role=\"presentation\" width=\"680\" cellspacing=\"0\" cellpadding=\"0\" style=\"width:100%;max-width:680px;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e5e7eb\">"
                + "<tr><td style=\"padding:24px 32px;background:#1d4ed8;color:#ffffff\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                + "<td style=\"font-size:18px;font-weight:700;color:#ffffff\">" + escapedSiteName + "</td>"
                + "<td align=\"right\" style=\"font-size:12px;color:#dbeafe\">文章更新通知</td>"
                + "</tr></table></td></tr>"
                + "<tr><td style=\"padding:32px 32px 12px\">"
                + "<h1 style=\"margin:0 0 12px;font-size:26px;line-height:1.35;font-weight:700;color:#111827\">" + escapedTitle + "</h1>"
                + metaAndSummary
                + "<hr style=\"border:none;border-top:1px solid #e5e7eb;margin:0 0 24px\">"
                + "<div style=\"font-size:16px;line-height:1.75;color:#1f2937;word-break:break-word\">" + safeBody + "</div>"
                + "<p style=\"margin:32px 0 16px;text-align:center\">"
                + "<a href=\"" + safeArticleUrl + "\" style=\"display:inline-block;padding:12px 26px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:8px;font-weight:600;font-size:15px\">在浏览器中阅读原文</a>"
                + "</p></td></tr>"
                + "<tr><td style=\"padding:20px 32px;background:#f8fafc;color:#64748b;font-size:12px;line-height:1.6;border-top:1px solid #e5e7eb\">"
                + "此邮件由 " + escapedSiteName + " 自动发送，感谢您的订阅。请勿直接回复此邮件。"
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /**
     * Renders an admin notification email with structured details, one-click action buttons,
     * and explicit plain-text fallback URLs for clients that do not render HTML properly.
     */
    public String renderAdminActionNotice(String siteName,
                                          String heading,
                                          String intro,
                                          List<DetailRow> details,
                                          String previewLabel,
                                          String previewText,
                                          List<ActionButton> actions,
                                          String referenceLabel,
                                          String referenceUrl) {
        String resolvedSiteName = (siteName == null || siteName.isBlank()) ? "WindBlog" : siteName.trim();
        String escapedSiteName = escape(resolvedSiteName);
        String escapedHeading = escape(heading);
        String escapedIntro = escape(intro).replace("\n", "<br>");

        StringBuilder detailsHtml = new StringBuilder();
        if (details != null && !details.isEmpty()) {
            detailsHtml.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin:0 0 20px;border-collapse:collapse;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px\">");
            for (DetailRow row : details) {
                if (row == null || row.value() == null || row.value().isBlank()) {
                    continue;
                }
                detailsHtml.append("<tr>")
                        .append("<td style=\"padding:10px 14px;width:110px;font-size:13px;font-weight:600;color:#475569;border-bottom:1px solid #e2e8f0;vertical-align:top\">")
                        .append(escape(row.label()))
                        .append("</td>")
                        .append("<td style=\"padding:10px 14px;font-size:14px;color:#0f172a;border-bottom:1px solid #e2e8f0;word-break:break-word\">")
                        .append(escape(row.value()).replace("\n", "<br>"))
                        .append("</td>")
                        .append("</tr>");
            }
            detailsHtml.append("</table>");
        }

        StringBuilder previewHtml = new StringBuilder();
        if (previewText != null && !previewText.isBlank()) {
            String label = (previewLabel == null || previewLabel.isBlank()) ? "内容详情" : previewLabel;
            previewHtml.append("<div style=\"margin:0 0 24px;padding:14px 16px;background:#f8fafc;border-left:4px solid #2563eb;border-radius:0 8px 8px 0\">")
                    .append("<div style=\"font-size:12px;font-weight:600;color:#64748b;margin-bottom:6px\">")
                    .append(escape(label))
                    .append("</div>")
                    .append("<div style=\"font-size:14px;line-height:1.65;color:#1e293b;word-break:break-word\">")
                    .append(escape(previewText).replace("\n", "<br>"))
                    .append("</div></div>");
        }

        StringBuilder buttonsHtml = new StringBuilder();
        StringBuilder fallbackLinksHtml = new StringBuilder();
        if (actions != null && !actions.isEmpty()) {
            buttonsHtml.append("<div style=\"margin:24px 0 18px\">");
            for (ActionButton action : actions) {
                if (action == null || action.url() == null || action.url().isBlank()) {
                    continue;
                }
                String bg = (action.backgroundHex() == null || action.backgroundHex().isBlank())
                        ? "#2563eb" : action.backgroundHex();
                buttonsHtml.append("<a href=\"").append(escapeAttribute(action.url()))
                        .append("\" style=\"display:inline-block;margin:0 12px 10px 0;padding:12px 22px;background:")
                        .append(escapeAttribute(bg))
                        .append(";color:#ffffff;text-decoration:none;border-radius:8px;font-weight:600;font-size:14px\">")
                        .append(escape(action.label()))
                        .append("</a>");
                fallbackLinksHtml.append("<div style=\"margin:6px 0;font-size:12px;line-height:1.5;color:#475569;word-break:break-all\">")
                        .append("<strong>").append(escape(action.label())).append("：</strong>")
                        .append("<a href=\"").append(escapeAttribute(action.url())).append("\" style=\"color:#2563eb\">")
                        .append(escape(action.url()))
                        .append("</a></div>");
            }
            buttonsHtml.append("</div>");
        }

        if (referenceUrl != null && !referenceUrl.isBlank()) {
            String refLabel = (referenceLabel == null || referenceLabel.isBlank()) ? "查看相关页面" : referenceLabel;
            fallbackLinksHtml.append("<div style=\"margin:8px 0 0;font-size:12px;line-height:1.5;color:#475569;word-break:break-all\">")
                    .append("<strong>").append(escape(refLabel)).append("：</strong>")
                    .append("<a href=\"").append(escapeAttribute(referenceUrl)).append("\" style=\"color:#2563eb\">")
                    .append(escape(referenceUrl))
                    .append("</a></div>");
        }

        String fallbackBox = fallbackLinksHtml.isEmpty() ? "" :
                "<div style=\"margin:20px 0 0;padding:14px 16px;background:#f1f5f9;border-radius:8px;border:1px solid #e2e8f0\">"
                        + "<div style=\"font-size:12px;font-weight:600;color:#334155;margin-bottom:6px\">"
                        + "备用操作链接（如邮件客户端未正确渲染按钮，请复制下方链接到浏览器打开）：</div>"
                        + fallbackLinksHtml
                        + "</div>";

        return "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',Arial,sans-serif;color:#1f2937\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#f4f7fb\"><tr><td align=\"center\" style=\"padding:32px 16px\">"
                + "<table role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\" style=\"width:100%;max-width:640px;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e5e7eb\">"
                + "<tr><td style=\"padding:24px 32px;background:#1d4ed8;color:#ffffff\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                + "<td style=\"font-size:18px;font-weight:700;color:#ffffff\">" + escapedSiteName + "</td>"
                + "<td align=\"right\" style=\"font-size:12px;color:#dbeafe\">管理员审核通知</td>"
                + "</tr></table></td></tr>"
                + "<tr><td style=\"padding:32px\">"
                + "<h1 style=\"margin:0 0 12px;font-size:22px;line-height:1.35;color:#111827\">" + escapedHeading + "</h1>"
                + "<p style=\"margin:0 0 20px;font-size:15px;line-height:1.65;color:#334155\">" + escapedIntro + "</p>"
                + detailsHtml
                + previewHtml
                + buttonsHtml
                + fallbackBox
                + "</td></tr>"
                + "<tr><td style=\"padding:18px 32px;background:#f8fafc;color:#64748b;font-size:12px;line-height:1.6;border-top:1px solid #e5e7eb\">"
                + "此邮件由 " + escapedSiteName + " 自动发送给网站管理员，一键操作链接 7 天内有效，请勿转发他人。"
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    public String renderAdminActionResultPage(String siteName, String title, String message, boolean success, String backUrl) {
        String resolvedSiteName = (siteName == null || siteName.isBlank()) ? "WindBlog" : siteName.trim();
        String badgeColor = success ? "#16a34a" : "#dc2626";
        String badgeText = success ? "操作成功" : "操作提示";
        String safeBackUrl = escapeAttribute((backUrl == null || backUrl.isBlank()) ? "/" : backUrl);
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">"
                + "<title>" + escape(title) + " - " + escape(resolvedSiteName) + "</title></head>"
                + "<body style=\"margin:0;padding:24px 16px;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',Arial,sans-serif;color:#1f2937\">"
                + "<div style=\"max-width:520px;margin:48px auto;background:#ffffff;border-radius:16px;border:1px solid #e5e7eb;overflow:hidden\">"
                + "<div style=\"padding:20px 28px;background:#1d4ed8;color:#ffffff;font-weight:700;font-size:18px\">"
                + escape(resolvedSiteName) + " · 邮件快捷审核</div>"
                + "<div style=\"padding:28px\">"
                + "<span style=\"display:inline-block;padding:4px 10px;border-radius:999px;background:" + badgeColor + ";color:#ffffff;font-size:12px;font-weight:600;margin-bottom:14px\">" + badgeText + "</span>"
                + "<h1 style=\"margin:0 0 12px;font-size:22px;color:#111827\">" + escape(title) + "</h1>"
                + "<p style=\"margin:0 0 24px;font-size:15px;line-height:1.7;color:#475569\">" + escape(message).replace("\n", "<br>") + "</p>"
                + "<a href=\"" + safeBackUrl + "\" style=\"display:inline-block;padding:11px 22px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:8px;font-weight:600;font-size:14px\">返回网站</a>"
                + "</div></div></body></html>";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String escapeAttribute(String value) {
        return escape(value).replace("'", "&#39;");
    }
}
