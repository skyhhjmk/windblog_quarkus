package com.biliwind.blog.common.helper;

import io.quarkus.qute.TemplateData;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.Collections;

@TemplateData
public final class MarkdownHelper {
    private static final String BLOG_URL = ConfigProvider.getConfig().getOptionalValue("blog.url", String.class).orElse("http://localhost:8080");

    private static final Parser PARSER = Parser.builder()
            .extensions(Collections.singletonList(TablesExtension.create()))
            .build();

    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder()
            .extensions(Collections.singletonList(TablesExtension.create()))
            .build();

    private static final Safelist POST_SAFE_LIST = Safelist.relaxed()
            .addTags("hr", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "span", "div", "button", "svg", "path", "rect", "line", "polyline", "mark", "kbd", "progress")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("span", "class", "style")
            .addAttributes("div", "class", "id", "style", "data-name")
            .addAttributes("table", "class")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            .addAttributes("button", "class", "data-post-id", "data-price", "data-block-id")
            .addAttributes("svg", "xmlns", "viewBox", "fill", "stroke", "stroke-width", "class", "width", "height")
            .addAttributes("rect", "x", "y", "width", "height", "rx", "ry")
            .addAttributes("path", "d")
            .addAttributes("line", "x1", "y1", "x2", "y2")
            .addAttributes("polyline", "points")
            .addAttributes("mark", "class")
            .addAttributes("kbd", "class")
            .addAttributes("progress", "class", "value", "max");

    public MarkdownHelper() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        // 1. Render to HTML first
        Node document = PARSER.parse(markdown);
        String html = HTML_RENDERER.render(document);

        // 2. Post-process the generated HTML (Avoids CommonMark escaping issues)
        // Inline tags
        html = html.replaceAll("==([^=]+?)==", "<mark>$1</mark>");
        html = html.replaceAll("\\[\\[(.+?)\\]\\]", "<kbd>$1</kbd>");
        html = html.replaceAll("\\[progress:(\\d+)\\]", "<progress value=\"$1\" max=\"100\"></progress>");
        html = html.replaceAll("\\[badge:(\\w+):(.+?)\\]", "<span class=\"badge badge-$1\">$2</span>");

        // Block containers (Handle the <p> tags added by commonmark)
        // Support both with and without leading spaces inside <p>
        html = html.replaceAll("(?s)<p>\\s*::: +(\\w+)\\s*</p>(.*?)<p>\\s*::: *</p>",
                "<div class=\"custom-block block-$1\" data-name=\"$1\">$2</div>");

        // 3. HTML Sanitization
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, BLOG_URL, POST_SAFE_LIST, outputSettings);
    }

    public String toHtml(Object markdown) {
        if (markdown == null) return "";
        return toHtml(markdown.toString());
    }
}
