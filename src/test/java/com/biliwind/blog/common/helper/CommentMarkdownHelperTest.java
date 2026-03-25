package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentMarkdownHelperTest {

    @Test
    void shouldKeepBasicMarkdownFormatting() {
        String html = CommentMarkdownHelper.toSafeHtml("**bold**\n\n> quote\n\n`code`");

        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.contains("<code>code</code>"));
    }

    @Test
    void shouldStripLinksAndImages() {
        String html = CommentMarkdownHelper.toSafeHtml("[link](https://example.com)\n\n![img](https://example.com/a.png)");

        assertFalse(html.contains("<a "));
        assertFalse(html.contains("<img "));
        assertTrue(html.contains("link"));
    }

    @Test
    void shouldStripRawScriptContent() {
        String html = CommentMarkdownHelper.toSafeHtml("<script>alert(1)</script><b>safe</b>");

        assertFalse(html.contains("<script"));
        assertFalse(html.contains("alert(1)"));
        assertTrue(html.contains("<b>safe</b>"));
    }
}
