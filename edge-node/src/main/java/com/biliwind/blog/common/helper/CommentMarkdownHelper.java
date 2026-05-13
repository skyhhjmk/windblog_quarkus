package com.biliwind.blog.common.helper;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

public final class CommentMarkdownHelper {

    private static final String BLOG_URL = ConfigProvider.getConfig().getOptionalValue("blog.url", String.class).orElse("http://localhost:8080");

    private static final Safelist COMMENT_SAFE_LIST = Safelist.none()
            .addTags(
                    "p", "br", "blockquote",
                    "strong", "b", "em", "i", "del",
                    "code", "pre",
                    "ul", "ol", "li")
            .addAttributes("code", "class")
            .addAttributes("pre", "class");

    private CommentMarkdownHelper() {
    }

    public static String toSafeHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String unsafeHtml = MarkdownHelper.toHtml(markdown);
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(unsafeHtml, BLOG_URL, COMMENT_SAFE_LIST, outputSettings);
    }
}
