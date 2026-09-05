package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarkdownHelper 单元测试
 * 测试 Markdown 转 HTML 功能
 */
class MarkdownHelperTest {

    @Test
    void shouldConvertSimpleMarkdownToHtml() {
        String markdown = "# Hello World";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<h1>"));
        assertTrue(html.contains("Hello World"));
        assertTrue(html.contains("</h1>"));
    }

    @Test
    void shouldConvertParagraphToHtml() {
        String markdown = "This is a paragraph.";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<p>"));
        assertTrue(html.contains("This is a paragraph."));
        assertTrue(html.contains("</p>"));
    }

    @Test
    void shouldConvertBoldTextToHtml() {
        String markdown = "**bold text**";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<strong>"));
        assertTrue(html.contains("bold text"));
        assertTrue(html.contains("</strong>"));
    }

    @Test
    void shouldConvertItalicTextToHtml() {
        String markdown = "*italic text*";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<em>"));
        assertTrue(html.contains("italic text"));
        assertTrue(html.contains("</em>"));
    }

    @Test
    void shouldConvertLinkToHtml() {
        String markdown = "[link text](https://example.com)";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<a "));
        assertTrue(html.contains("href=\"https://example.com\""));
        assertTrue(html.contains("link text"));
    }

    @Test
    void shouldConvertCodeBlockToHtml() {
        String markdown = "```\ncode block\n```";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<pre>"));
        assertTrue(html.contains("<code>"));
    }

    @Test
    void shouldConvertInlineCodeToHtml() {
        String markdown = "`inline code`";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<code>"));
        assertTrue(html.contains("inline code"));
    }

    @Test
    void shouldConvertListToHtml() {
        String markdown = "- item 1\n- item 2\n- item 3";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<li>"));
        assertTrue(html.contains("item 1"));
    }

    @Test
    void shouldConvertOrderedListToHtml() {
        String markdown = "1. first\n2. second\n3. third";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<ol>"));
        assertTrue(html.contains("<li>"));
        assertTrue(html.contains("first"));
    }

    @Test
    void shouldRenderGfmTableWithAlignmentAndEscapedPipe() {
        String markdown = """
                | 项目 | 结论 | 备注 |
                | :--- | :---: | ---: |
                | 渲染 | 正常 | `A|B` |
                """;

        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<thead>"));
        assertTrue(html.contains("align=\"center\""));
        assertTrue(html.contains("<code>A|B</code>"));
    }

    @Test
    void shouldReturnEmptyStringForNullInput() {
        String html = MarkdownHelper.toHtml(null);

        assertEquals("", html);
    }

    @Test
    void shouldReturnEmptyStringForEmptyInput() {
        String html = MarkdownHelper.toHtml("");

        assertEquals("", html);
    }

    @Test
    void shouldReturnEmptyStringForBlankInput() {
        String html = MarkdownHelper.toHtml("   \n\t  ");

        assertEquals("", html);
    }

    @Test
    void shouldConvertComplexMarkdownToHtml() {
        String markdown = """
                # Title
                
                This is a paragraph with **bold** and *italic* text.
                
                ## Section
                
                - Item 1
                - Item 2
                
                [Link](https://example.com)
                """;
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<h1>"));
        assertTrue(html.contains("<h2>"));
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<strong>"));
        assertTrue(html.contains("<em>"));
        assertTrue(html.contains("<a "));
    }

    @Test
    void shouldConvertBlockquoteToHtml() {
        String markdown = "> This is a quote";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.contains("This is a quote"));
    }

    @Test
    void shouldConvertHorizontalRuleToHtml() {
        String markdown = "---";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("<hr"));
    }

    @Test
    void shouldConvertCustomContainerWithAttributesToHtml() {
        String markdown = "::: detailed {group=踩坑, title=\"深度分析\"}\n踩坑记录在这里\n::: /detailed";
        String html = MarkdownHelper.toHtml(markdown);

        assertTrue(html.contains("class=\"custom-block block-detailed\""));
        assertTrue(html.contains("data-name=\"detailed\""));
        assertTrue(html.contains("data-group=\"踩坑\""));
        assertTrue(html.contains("data-title=\"深度分析\""));
        assertTrue(html.contains("data-block-id=\"block-"));
        assertTrue(html.contains("踩坑记录在这里"));
    }

    @Test
    void shouldRemoveMarkdownHtmlScriptStyleSvgAndUnsafeImagePayloads() {
        String markdown = "<script>alert(1)</script><div style=\"background:url(https://evil.example)\">text</div>\n\n"
                + "<svg onload=alert(1)><path /></svg>\n\n"
                + "![x](data:image/svg+xml;base64,AAAA)\n\n"
                + "[bad](javascript:alert(1))";

        String html = MarkdownHelper.toHtml(markdown);

        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<script"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("style="));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("onload"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("data:image"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("javascript:"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("evil.example"));
    }
}
