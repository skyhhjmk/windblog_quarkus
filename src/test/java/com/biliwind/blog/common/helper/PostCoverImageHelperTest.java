package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PostCoverImageHelperTest {

    @Test
    void shouldUseTheFirstMarkdownImage() {
        String markdown = "Intro text\n\n![cover](/uploads/a.webp)\n\n![later](/uploads/b.webp)";
        assertEquals("/uploads/a.webp", PostCoverImageHelper.firstImageUrl(markdown));
    }

    @Test
    void shouldRespectDocumentOrderAcrossMarkdownAndHtmlImages() {
        String htmlFirst = "<img src=\"/uploads/html.webp\">\n![md](/uploads/md.webp)";
        String markdownFirst = "![md](/uploads/md.webp)\n<img src=\"/uploads/html.webp\">";

        assertEquals("/uploads/html.webp", PostCoverImageHelper.firstImageUrl(htmlFirst));
        assertEquals("/uploads/md.webp", PostCoverImageHelper.firstImageUrl(markdownFirst));
    }

    @Test
    void shouldIgnoreHiddenBlocksCodeAndDisallowedHosts() {
        String markdown = "[hide-text id=\"1\"]![secret](/uploads/secret.webp)[/hide-text]\n"
                + "```\n![code](/uploads/code.webp)\n```\n"
                + "`![inline](/uploads/inline.webp)`\n"
                + "![remote](https://evil.example/cover.webp)\n"
                + "![real](/uploads/real.webp)";

        assertEquals("/uploads/real.webp", PostCoverImageHelper.firstImageUrl(markdown));
    }

    @Test
    void shouldFallBackToAnotherTranslationWhenPreferredLanguageHasNoImage() {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("zh-cn", "只有文字，没有图片。");
        content.put("en-us", "Text only here too.\n\n![cover](/uploads/en-cover.webp)");

        assertEquals("/uploads/en-cover.webp",
                PostCoverImageHelper.firstImageUrl(content, "zh-cn"));
    }

    @Test
    void shouldReturnNullWhenNoRenderableImageExists() {
        assertNull(PostCoverImageHelper.firstImageUrl((String) null));
        assertNull(PostCoverImageHelper.firstImageUrl("plain text only"));
        assertNull(PostCoverImageHelper.firstImageUrl(Map.of(), "zh-cn"));
    }
}
