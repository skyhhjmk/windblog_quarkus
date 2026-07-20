package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EmailTemplateRenderer {
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

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String escapeAttribute(String value) {
        return escape(value).replace("'", "&#39;");
    }
}
