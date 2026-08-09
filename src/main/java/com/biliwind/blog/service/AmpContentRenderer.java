package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.model.PostRenderType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the already public-safe post body to the small subset supported by AMP.
 * Interactive and executable content is removed instead of being copied into a
 * cacheable public AMP document.
 */
@ApplicationScoped
public class AmpContentRenderer {

    private static final int DEFAULT_IMAGE_WIDTH = 800;
    private static final int DEFAULT_IMAGE_HEIGHT = 450;

    @Inject
    PublicContentSanitizer publicContentSanitizer;

    public RenderedContent render(PostRenderType renderType, String content) {
        String rawHtml = toRawHtml(renderType, content);
        int removedElementCount = countUnsupportedElements(rawHtml);
        String html = publicContentSanitizer.sanitize(rawHtml);
        Document document = Jsoup.parseBodyFragment(html);
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        document.outputSettings(outputSettings);

        int imageCount = 0;

        List<Element> unsupportedElements = new ArrayList<>();
        for (Element element : document.body().select("script,style,noscript,iframe,object,embed,form,input,button,video,audio,canvas,svg")) {
            unsupportedElements.add(element);
        }
        for (Element element : unsupportedElements) {
            element.remove();
        }

        List<Element> images = new ArrayList<>();
        for (Element image : document.body().select("img")) {
            images.add(image);
        }
        for (Element image : images) {
            String source = image.attr("src").trim();
            if (source.isEmpty()) {
                image.remove();
                removedElementCount++;
                continue;
            }

            Element ampImage = document.createElement("amp-img");
            ampImage.attr("src", source);
            ampImage.attr("alt", image.attr("alt"));
            ampImage.attr("width", positiveDimension(image.attr("width"), DEFAULT_IMAGE_WIDTH));
            ampImage.attr("height", positiveDimension(image.attr("height"), DEFAULT_IMAGE_HEIGHT));
            ampImage.attr("layout", "responsive");
            image.replaceWith(ampImage);
            imageCount++;
        }

        for (Element anchor : document.body().select("a")) {
            anchor.attr("rel", "nofollow noopener noreferrer");
        }

        return new RenderedContent(document.body().html(), imageCount, removedElementCount);
    }

    private String toRawHtml(PostRenderType renderType, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        PostRenderType effectiveRenderType = renderType;
        if (effectiveRenderType == null) {
            effectiveRenderType = PostRenderType.MARKDOWN;
        }

        String html;
        if (effectiveRenderType == PostRenderType.MARKDOWN
                || effectiveRenderType == PostRenderType.FLUTTER_MARKDOWN_PLUS) {
            html = MarkdownHelper.toHtml(content);
        } else {
            html = content;
        }
        return html;
    }

    private int countUnsupportedElements(String html) {
        if (html == null || html.isBlank()) {
            return 0;
        }
        Document document = Jsoup.parseBodyFragment(html);
        return document.body().select("script,style,noscript,iframe,object,embed,form,input,button,video,audio,canvas,svg").size();
    }

    private String positiveDimension(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0 && parsed <= 4096) {
                return String.valueOf(parsed);
            }
        } catch (NumberFormatException ignored) {
            // Use a stable responsive fallback when an author supplied no valid size.
        }
        return String.valueOf(fallback);
    }

    public record RenderedContent(String html, int imageCount, int removedElementCount) {
    }
}
