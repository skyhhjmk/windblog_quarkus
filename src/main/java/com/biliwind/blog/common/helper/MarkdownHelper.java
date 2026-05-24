package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.markdown.MdProtocolExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.quarkus.qute.TemplateData;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.Collections;

@TemplateData
public final class MarkdownHelper {
    // 文章内容允许的标签（比评论更宽松，允许图片、表格等）
    private static final Safelist POST_SAFE_LIST = Safelist.relaxed()
            .addTags("hr", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "span", "div", "button", "svg", "path", "rect", "line", "polyline")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("span", "class", "style")
            .addAttributes("div", "class", "id", "style")
            .addAttributes("a", "href", "target", "rel", "class", "title",
                    "data-article-link-preview", "data-link-name", "data-link-url",
                    "data-link-description", "data-link-icon")
            .addAttributes("table", "class")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            // 允许卡片购买按钮属性
            .addAttributes("button", "class", "data-post-id", "data-price", "data-block-id")
            // 允许卡片内联 SVG 属性
            .addAttributes("svg", "xmlns", "viewBox", "fill", "stroke", "stroke-width", "class", "width", "height")
            .addAttributes("rect", "x", "y", "width", "height", "rx", "ry")
            .addAttributes("path", "d")
            .addAttributes("line", "x1", "y1", "x2", "y2")
            .addAttributes("polyline", "points");

    public MarkdownHelper() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String unsafeHtml = FlexmarkHolder.HTML_RENDERER.render(FlexmarkHolder.PARSER.parse(markdown));

        // 执行 HTML 净化，并提供基础 URL 以补全相对路径
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(unsafeHtml, FlexmarkHolder.BLOG_URL, POST_SAFE_LIST, outputSettings);
    }

    private static MutableDataSet createFlexmarkOptions() {
        MutableDataSet flexmarkOptions = new MutableDataSet();
        flexmarkOptions.set(Parser.EXTENSIONS, Collections.singletonList(MdProtocolExtension.create()));
        flexmarkOptions.set(HtmlRenderer.SOFT_BREAK, "<br />\n");
        return flexmarkOptions;
    }

    public String toHtml(Object markdown) {
        if (markdown == null) {
            return "";
        }
        return toHtml(markdown.toString());
    }

    private static final class FlexmarkHolder {
        private static final String BLOG_URL = ConfigProvider.getConfig()
                .getOptionalValue("blog.url", String.class)
                .orElse("http://localhost:8080");

        private static final MutableDataSet OPTIONS = createFlexmarkOptions();

        private static final Parser PARSER = Parser.builder(OPTIONS).build();
        private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder(OPTIONS).build();
    }
}
