package com.biliwind.blog.service;

/**
 * One public page as a document/fragment pair. The template ids live here so a
 * controller names the page once and never chooses between the two renderings;
 * {@link PageRenderer} makes that choice from the request.
 */
public enum PageView {

    INDEX("blog/index.html", "blog/index.content.html"),
    POST("blog/post.html", "blog/post.content.html"),
    CATEGORY("blog/category.html", "blog/category.content.html"),
    TAG("blog/tag.html", "blog/tag.content.html"),
    SEARCH("blog/search.html", "blog/search.content.html"),
    TOOLS("tools/index.html", "tools/index.content.html"),
    TOOLS_SUBNET("tools/subnet.html", "tools/subnet.content.html"),
    TOOLS_TIMESTAMP("tools/timestamp.html", "tools/timestamp.content.html"),
    TOOLS_LOG_FORMATTER("tools/log-formatter.html", "tools/log-formatter.content.html"),
    TOOLS_BASE_ENCODER("tools/base-encoder.html", "tools/base-encoder.content.html"),
    TOOLS_CRON("tools/cron.html", "tools/cron.content.html"),
    TOOLS_REGEX("tools/regex.html", "tools/regex.content.html"),
    TOOLS_HASH("tools/hash.html", "tools/hash.content.html"),
    TOOLS_JSON_DIFF("tools/json-diff.html", "tools/json-diff.content.html"),
    TOOLS_SERIAL("tools/serial.html", "tools/serial.content.html"),
    LEGAL_TERMS("legal/terms.html", "legal/terms.content.html"),
    LEGAL_PRIVACY("legal/privacy.html", "legal/privacy.content.html"),
    LINK("blog/link.html", "blog/link.content.html"),
    LINK_APPLY("blog/link-apply.html", "blog/link-apply.content.html"),
    LINK_DETAIL("blog/link-detail.html", "blog/link-detail.content.html"),
    USER_LOGIN("user/login.html", "user/login.content.html"),
    USER_REGISTER("user/register.html", "user/register.content.html"),
    USER_FORGOT_PASSWORD("user/forgot-password.html", "user/forgot-password.content.html"),
    USER_RESET_PASSWORD("user/reset-password.html", "user/reset-password.content.html"),
    USER_CENTER("user/center.html", "user/center.content.html"),
    OFFLINE("system/offline.html", "system/offline.content.html"),
    PASSWORD("blog/password.html", "blog/password.content.html");

    private final String documentId;
    private final String fragmentId;

    PageView(String documentId, String fragmentId) {
        this.documentId = documentId;
        this.fragmentId = fragmentId;
    }

    public String documentId() {
        return documentId;
    }

    public String fragmentId() {
        return fragmentId;
    }
}
