package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Unified Markdown Protocol Extension for WindBlog.
 */
public class MdProtocolExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

    private MdProtocolExtension() {
    }

    public static MdProtocolExtension create() {
        return new MdProtocolExtension();
    }

    @Override
    public void parserOptions(@NotNull MutableDataHolder options) {
    }

    @Override
    public void extend(Parser.Builder parserBuilder) {
        parserBuilder.customBlockParserFactory(new CustomContainerBlockParser.Factory());
        parserBuilder.customBlockParserFactory(new CalloutBlockParser.Factory());
        parserBuilder.customDelimiterProcessor(new HighlightDelimiterProcessor());
        parserBuilder.customInlineParserExtensionFactory(new MdInlineParserExtension.Factory());
    }

    @Override
    public void rendererOptions(@NotNull MutableDataHolder options) {
    }

    @Override
    public void extend(@NotNull HtmlRenderer.Builder htmlRendererBuilder, @NotNull String rendererType) {
        if (htmlRendererBuilder.isRendererType("HTML")) {
            htmlRendererBuilder.nodeRendererFactory(new MdProtocolNodeRenderer.Factory());
        }
    }
}
