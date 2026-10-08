package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.markdown.MdProtocolExtension;
import com.biliwind.blog.model.Media;
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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@TemplateData
public final class MarkdownHelper {
    private static final Logger log = LoggerFactory.getLogger(MarkdownHelper.class);
    private static final Pattern IMPORT_PLACEHOLDER_PATH =
            Pattern.compile("^/uploads/import-placeholder/(\\d+)$");

    // 文章内容允许的标签；脚本、内联样式和 SVG 不属于文章数据，避免内容域承担主动内容。
    private static final Safelist POST_SAFE_LIST = Safelist.relaxed()
            .addTags("hr", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "span", "div", "button")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("span", "class")
            .addAttributes("div", "class", "id", "data-name", "data-group", "data-title", "data-block-id")
            .addAttributes("img", "width", "height", "loading", "decoding", "class")
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

        // 净化 HTML 时提供校验基准，同时保留由当前区域主机解析的相对路径。
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        // Keep first-party relative URLs relative. The configured public URL may point at
        // another region's domain, while /uploads/... must resolve against this request host.
        String sanitized = Jsoup.clean(unsafeHtml, getBlogUrl(), POST_SAFE_LIST.preserveRelativeLinks(true), outputSettings);
        Document document = Jsoup.parseBodyFragment(sanitized);
        PublicMediaUrlPolicy.removeDisallowedImages(document);
        injectMediaDimensions(document);
        for (org.jsoup.nodes.Element anchor : document.select("a")) {
            anchor.attr("rel", "nofollow noopener noreferrer");
        }
        return document.body().html();
    }

    private static void injectMediaDimensions(Document document) {
        Map<org.jsoup.nodes.Element, ImageMediaReference> references = new LinkedHashMap<>();
        Set<Long> mediaIds = new LinkedHashSet<>();
        Set<String> storageKeys = new LinkedHashSet<>();

        for (org.jsoup.nodes.Element image : document.select("img[src]")) {
            if (hasUsableDimensions(image)) {
                continue;
            }
            String source = image.attr("src").trim();
            String path = imagePath(source);
            if (path == null) {
                continue;
            }

            Matcher placeholderMatcher = IMPORT_PLACEHOLDER_PATH.matcher(path);
            if (placeholderMatcher.matches()) {
                try {
                    long id = Long.parseLong(placeholderMatcher.group(1));
                    references.put(image, new ImageMediaReference(id, null));
                    mediaIds.add(id);
                } catch (NumberFormatException ignored) {
                    // An invalid ID cannot be resolved to media dimensions.
                }
                continue;
            }

            if (path.startsWith("/uploads/")) {
                String fileName = path.substring("/uploads/".length());
                if (!fileName.isBlank() && !fileName.contains("/")) {
                    references.put(image, new ImageMediaReference(null, fileName));
                    storageKeys.add(fileName);
                }
            }
        }
        if (references.isEmpty()) {
            return;
        }

        try {
            Map<Long, Media> mediaById = new LinkedHashMap<>();
            Map<String, Media> mediaByStorageKey = new LinkedHashMap<>();
            if (!mediaIds.isEmpty()) {
                for (Media media : Media.<Media>find("id in ?1", mediaIds).list()) {
                    mediaById.put(media.id, media);
                }
            }
            if (!storageKeys.isEmpty()) {
                for (Media media : Media.<Media>find("storageKey in ?1", storageKeys).list()) {
                    mediaByStorageKey.put(media.storageKey, media);
                }
            }

            Set<Long> duplicateTargetIds = new LinkedHashSet<>();
            for (Long mediaId : mediaIds) {
                Media media = mediaById.get(mediaId);
                Long duplicateTargetId = duplicateTargetId(media);
                if (duplicateTargetId != null && !mediaById.containsKey(duplicateTargetId)) {
                    duplicateTargetIds.add(duplicateTargetId);
                }
            }
            if (!duplicateTargetIds.isEmpty()) {
                for (Media media : Media.<Media>find("id in ?1", duplicateTargetIds).list()) {
                    mediaById.put(media.id, media);
                }
            }

            for (Map.Entry<org.jsoup.nodes.Element, ImageMediaReference> entry : references.entrySet()) {
                ImageMediaReference reference = entry.getValue();
                Media media = reference.mediaId() != null
                        ? mediaById.get(reference.mediaId())
                        : mediaByStorageKey.get(reference.storageKey());
                Long duplicateTargetId = duplicateTargetId(media);
                if (duplicateTargetId != null) {
                    media = mediaById.getOrDefault(duplicateTargetId, media);
                }
                if (media == null || media.mediaType != 0) {
                    continue;
                }
                int[] dimensions = mediaDimensions(media);
                if (dimensions == null) {
                    continue;
                }
                org.jsoup.nodes.Element image = entry.getKey();
                image.attr("width", String.valueOf(dimensions[0]));
                image.attr("height", String.valueOf(dimensions[1]));
                if (image.attr("loading").isBlank()) {
                    image.attr("loading", "lazy");
                }
                if (image.attr("decoding").isBlank()) {
                    image.attr("decoding", "async");
                }
            }
        } catch (RuntimeException exception) {
            // Non-request render paths (for example search indexing) may not have a
            // Hibernate session. Rendering remains safe; it simply omits dimensions.
            log.debug("无法为 Markdown 图片补充媒体尺寸", exception);
        }
    }

    private static int[] mediaDimensions(Media media) {
        if (isUsableDimension(media.width) && isUsableDimension(media.height)) {
            return new int[]{media.width, media.height};
        }

        Integer metadataWidth = metadataDimension(media, "width");
        Integer metadataHeight = metadataDimension(media, "height");
        if (isUsableDimension(metadataWidth) && isUsableDimension(metadataHeight)) {
            return new int[]{metadataWidth, metadataHeight};
        }

        if (media.storageKey == null || media.storageKey.isBlank()) {
            return null;
        }
        try {
            String uploadDir = ConfigProvider.getConfig()
                    .getOptionalValue("media.upload.dir", String.class).orElse("uploads");
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path key = Paths.get(media.storageKey);
            if (key.isAbsolute() || key.getNameCount() != 1) {
                return null;
            }
            Path file = root.resolve(key).normalize();
            return file.startsWith(root) ? ImageDimensionReader.read(file) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Integer metadataDimension(Media media, String key) {
        if (media.metadata == null) {
            return null;
        }
        Object value = media.metadata.get(key);
        try {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String imagePath(String source) {
        try {
            URI uri = URI.create(source);
            if (uri.getScheme() != null || source.startsWith("//")) {
                return uri.getPath();
            }
            return source.startsWith("/") ? uri.getPath() : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long duplicateTargetId(Media media) {
        if (media == null || media.metadata == null) {
            return null;
        }
        Object value = media.metadata.get("duplicateOfMediaId");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasUsableDimensions(org.jsoup.nodes.Element image) {
        return isUsableDimension(parseDimension(image.attr("width")))
                && isUsableDimension(parseDimension(image.attr("height")));
    }

    private static Integer parseDimension(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isUsableDimension(Integer dimension) {
        return dimension != null && dimension > 0 && dimension <= 100_000;
    }

    private record ImageMediaReference(Long mediaId, String storageKey) {
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
