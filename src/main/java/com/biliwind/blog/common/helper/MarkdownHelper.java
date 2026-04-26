package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.markdown.MdProtocolExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.Collections;
import java.util.List;

public final class MarkdownHelper {

    private static final MutableDataSet OPTIONS = new MutableDataSet()
            .set(Parser.EXTENSIONS, Collections.singletonList(MdProtocolExtension.create()))
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n");

    private static final Parser PARSER = Parser.builder(OPTIONS).build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder(OPTIONS).build();

    private MarkdownHelper() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return HTML_RENDERER.render(PARSER.parse(markdown));
    }
}
