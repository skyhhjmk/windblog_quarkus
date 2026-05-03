package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.markdown.MdProtocolExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.Collections;

public final class MarkdownHelper {

    private static final MutableDataSet OPTIONS = new MutableDataSet()
            .set(Parser.EXTENSIONS, Collections.singletonList(MdProtocolExtension.create()))
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n");

    private static final Parser PARSER = Parser.builder(OPTIONS).build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder(OPTIONS).build();

    // 文章内容允许的标签（比评论更宽松，允许图片、表格等）
    private static final Safelist POST_SAFE_LIST = Safelist.relaxed()
            .addTags("hr", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "span", "div")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("span", "class", "style")
            .addAttributes("div", "class", "id", "style")
            .addAttributes("table", "class")
            .addAttributes("th", "align")
            .addAttributes("td", "align");

    private MarkdownHelper() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String unsafeHtml = HTML_RENDERER.render(PARSER.parse(markdown));

        // 执行 HTML 净化
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(unsafeHtml, "", POST_SAFE_LIST, outputSettings);
    }
}
