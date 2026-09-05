package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.markdown.MdProtocolExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.quarkus.qute.TemplateData;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import com.biliwind.blog.service.PublicMediaUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@TemplateData
public final class MarkdownHelper {
    private static final Logger log = LoggerFactory.getLogger(MarkdownHelper.class);

    // 文章内容允许的标签；脚本、内联样式和 SVG 不属于文章数据，避免内容域承担主动内容。
    private static final Safelist POST_SAFE_LIST = Safelist.relaxed()
            .addTags("hr", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "span", "div", "button")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("span", "class")
            .addAttributes("div", "class", "id", "data-name", "data-group", "data-title", "data-block-id")
            .addAttributes("a", "href", "target", "rel", "class", "title",
                    "data-article-link-preview", "data-link-name", "data-link-url",
                    "data-link-description", "data-link-icon")
            .addAttributes("table", "class")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            // 允许卡片购买按钮属性
            .addAttributes("button", "class", "data-post-id", "data-price", "data-block-id")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    public MarkdownHelper() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String unsafeHtml = renderMarkdown(markdown);

        // 执行 HTML 净化，并提供基础 URL 以补全相对路径
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        String sanitized = Jsoup.clean(unsafeHtml, getBlogUrl(), POST_SAFE_LIST, outputSettings);
        Document document = Jsoup.parseBodyFragment(sanitized);
        PublicMediaUrlPolicy.removeDisallowedImages(document);
        for (org.jsoup.nodes.Element anchor : document.select("a")) {
            anchor.attr("rel", "nofollow noopener noreferrer");
        }
        return document.body().html();
    }

    private static String renderMarkdown(String markdown) {
        try {
            MarkdownRuntime runtime = getMarkdownRuntime();
            return runtime.htmlRenderer.render(runtime.parser.parse(markdown));
        } catch (Throwable throwable) {
            log.error("Markdown 渲染器初始化或渲染失败，已降级为安全纯文本输出", throwable);
            return renderPlainTextFallback(markdown);
        }
    }

    private static MarkdownRuntime getMarkdownRuntime() {
        MarkdownRuntime currentRuntime = MarkdownRuntimeHolder.markdownRuntime;
        if (currentRuntime != null) {
            return currentRuntime;
        }

        synchronized (MarkdownHelper.class) {
            MarkdownRuntime synchronizedRuntime = MarkdownRuntimeHolder.markdownRuntime;
            if (synchronizedRuntime != null) {
                return synchronizedRuntime;
            }
            MarkdownRuntime createdRuntime = createMarkdownRuntime();
            MarkdownRuntimeHolder.markdownRuntime = createdRuntime;
            return createdRuntime;
        }
    }

    private static MarkdownRuntime createMarkdownRuntime() {
        MutableDataSet flexmarkOptions = new MutableDataSet();
        // WindBlog stores Markdown that is also edited by GFM-compatible clients.  Register the
        // table parser explicitly: flexmark-all provides the extension but does not enable it.
        flexmarkOptions.set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                MdProtocolExtension.create()));
        flexmarkOptions.set(HtmlRenderer.SOFT_BREAK, "<br />\n");
        Parser parser = Parser.builder(flexmarkOptions).build();
        HtmlRenderer htmlRenderer = HtmlRenderer.builder(flexmarkOptions).build();
        return new MarkdownRuntime(parser, htmlRenderer);
    }

    private static String renderPlainTextFallback(String markdown) {
        String escapedText = markdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
        return escapedText.replace("\n", "<br />\n");
    }

    private static String getBlogUrl() {
        return ConfigProvider.getConfig()
                .getOptionalValue("windblog.site.public-url", String.class)
                .orElse("http://localhost:8080");
    }

    public String toHtml(Object markdown) {
        if (markdown == null) {
            return "";
        }
        return toHtml(markdown.toString());
    }

    private static final class MarkdownRuntimeHolder {
        private static volatile MarkdownRuntime markdownRuntime;
    }

    private static final class MarkdownRuntime {
        private final Parser parser;
        private final HtmlRenderer htmlRenderer;

        private MarkdownRuntime(Parser parser, HtmlRenderer htmlRenderer) {
            this.parser = parser;
            this.htmlRenderer = htmlRenderer;
        }
    }
}
