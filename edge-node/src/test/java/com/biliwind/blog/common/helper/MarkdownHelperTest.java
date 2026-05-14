package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MarkdownHelperTest {

    @Test
    public void testStandardMarkdown() {
        String md = "# Heading\n\n**Bold** and *Italic*\n\n| Col1 | Col2 |\n|------|------|\n| Val1 | Val2 |";
        String html = MarkdownHelper.toHtml(md);
        System.out.println("DEBUG HTML: " + html);
        Assertions.assertTrue(html.contains("<h1>Heading</h1>"));
        Assertions.assertTrue(html.contains("<strong>Bold</strong>"));
        Assertions.assertTrue(html.contains("<em>Italic</em>"));
        Assertions.assertTrue(html.contains("<table>"));
        Assertions.assertTrue(html.contains("<thead>"));
        Assertions.assertTrue(html.contains("Val1"));
    }

    @Test
    public void testCustomSyntax() {
        String md = "Highlight: ==important==\n" +
                "Kbd: [[Ctrl+C]]\n" +
                "Progress: [progress:75]\n" +
                "Badge: [badge:info:New]";
        String html = MarkdownHelper.toHtml(md);
        System.out.println("DEBUG HTML (Syntax): " + html);

        Assertions.assertTrue(html.contains("<mark>important</mark>"));
        Assertions.assertTrue(html.contains("<kbd>Ctrl+C</kbd>"));
        Assertions.assertTrue(html.contains("<progress value=\"75\" max=\"100\"></progress>"));
        Assertions.assertTrue(html.contains("<span class=\"badge badge-info\">New</span>"));
    }

    @Test
    public void testCustomContainer() {
        String md = "::: tip\n" +
                "\n" +
                "This is a tip\n" +
                "with multiple lines.\n" +
                "\n" +
                ":::";
        String html = MarkdownHelper.toHtml(md);
        System.out.println("DEBUG HTML (Container): " + html);

        Assertions.assertTrue(html.contains("<div class=\"custom-block block-tip\" data-name=\"tip\">"));
        Assertions.assertTrue(html.contains("This is a tip"));
        Assertions.assertTrue(html.contains("multiple lines."));
    }

    @Test
    public void testMessyInput() {
        String md = "::: warning\n" +
                "\n" +
                "  \n" +
                "Messy warning with extra spaces and newlines\n" +
                "\n" +
                ":::\n" +
                "\n" +
                "Mixed: ==highlight== and [[kbd]] with [progress:10]\n" +
                "\n" +
                "Empty lines between == highlight ==? No, shouldn't match if spaced incorrectly by design,\n" +
                "but let's see: ==\n" +
                "broken==\n" +
                "\n" +
                "Standard table with gaps:\n" +
                "\n" +
                "| A | B |\n" +
                "|---|---|\n" +
                "| 1 | 2 |";

        String html = MarkdownHelper.toHtml(md);
        System.out.println("DEBUG HTML (Messy): " + html);

        // Check container
        Assertions.assertTrue(html.contains("<div class=\"custom-block block-warning\" data-name=\"warning\">"));
        Assertions.assertTrue(html.contains("Messy warning"));

        // Check mixed
        Assertions.assertTrue(html.contains("<mark>highlight</mark>"));
        Assertions.assertTrue(html.contains("<kbd>kbd</kbd>"));
        Assertions.assertTrue(html.contains("<progress value=\"10\" max=\"100\"></progress>"));

        // Check table
        Assertions.assertTrue(html.contains("<table>"));
    }

    @Test
    public void testHtmlSanitization() {
        String md = "Safe text <script>alert('xss')</script> <img src=x onerror=alert(1)>";
        String html = MarkdownHelper.toHtml(md);

        Assertions.assertFalse(html.contains("<script>"));
        Assertions.assertFalse(html.contains("onerror"));
        Assertions.assertTrue(html.contains("Safe text"));
    }
}
