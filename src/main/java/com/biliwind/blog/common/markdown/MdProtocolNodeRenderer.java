package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class MdProtocolNodeRenderer implements NodeRenderer {

    @Override
    public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
        Set<NodeRenderingHandler<?>> handlers = new HashSet<>();
        handlers.add(new NodeRenderingHandler<>(MdNodes.CustomContainerBlock.class, this::renderCustomContainer));
        handlers.add(new NodeRenderingHandler<>(com.vladsch.flexmark.ast.BlockQuote.class, this::renderBlockQuote));
        handlers.add(new NodeRenderingHandler<>(MdNodes.CalloutBlock.class, this::renderCallout));
        handlers.add(new NodeRenderingHandler<>(MdNodes.HighlightNode.class, this::renderHighlight));
        handlers.add(new NodeRenderingHandler<>(MdNodes.KeyboardNode.class, this::renderKeyboard));
        handlers.add(new NodeRenderingHandler<>(MdNodes.ProgressNode.class, this::renderProgress));
        handlers.add(new NodeRenderingHandler<>(MdNodes.StatusBadgeNode.class, this::renderStatusBadge));
        return handlers;
    }

    private void renderCustomContainer(MdNodes.CustomContainerBlock node, NodeRendererContext context, HtmlWriter html) {
        if (!node.isClosed()) {
            html.attr("class", "error-block").attr("style", "color: red; border: 1px solid red; padding: 10px; margin: 10px 0;")
                    .withAttr()
                    .tag("div");
            html.text("解析错误：缺少闭合标签 ::: /" + node.getName());
            html.tag("/div");
            return;
        }

        com.biliwind.blog.model.BlogRegion currentRegion = getCurrentRegion();
        if (node.isExcluded(currentRegion)) {
            return;
        }

        String blockId = "block-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        String name = node.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "basic";
        }
        String normalizedName = name.trim().toLowerCase(java.util.Locale.ROOT);
        if ("asciinema-player".equals(normalizedName) || "asciinema".equals(normalizedName)) {
            renderAsciinemaContainer(node, html, blockId);
            return;
        }

        html.attr("class", "article".equals(name)
                ? "article-reference-card" : "custom-block block-" + name)
                .attr("data-name", name)
                .attr("data-block-id", blockId);

        if (node.getGroup() != null && !node.getGroup().isBlank()) {
            html.attr("data-group", node.getGroup());
        }
        if (node.getTitle() != null && !node.getTitle().isBlank()) {
            html.attr("data-title", node.getTitle());
        }

        html.withAttr().tag("div");
        context.renderChildren(node);
        html.tag("/div");
    }

    private void renderAsciinemaContainer(MdNodes.CustomContainerBlock node, HtmlWriter html, String blockId) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (node.getAttributes() != null) {
            for (var entry : node.getAttributes().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    merged.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue().trim());
                }
            }
        }

        String linkUrl = null;
        String linkText = null;
        com.vladsch.flexmark.ast.Link firstLink = findFirstLink(node);
        if (firstLink != null) {
            linkUrl = firstLink.getUrl().toString().trim();
            linkText = firstLink.getText().toString().trim();
        }

        String rawChildText = node.getChildChars() == null ? "" : node.getChildChars().toString();
        for (String rawLine : rawChildText.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            if ((line.startsWith("/") && !line.startsWith("//"))
                    || line.startsWith("http://")
                    || line.startsWith("https://")) {
                if (linkUrl == null || linkUrl.isBlank()) {
                    linkUrl = line;
                }
                continue;
            }
            int sep = line.indexOf('=');
            int colon = line.indexOf(':');
            if (sep < 0 || (colon > 0 && colon < sep)) {
                sep = colon;
            }
            if (sep > 0) {
                String k = line.substring(0, sep).trim().toLowerCase(java.util.Locale.ROOT);
                String v = line.substring(sep + 1).trim();
                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1).trim();
                }
                if (!k.isEmpty() && !v.isEmpty()) {
                    merged.putIfAbsent(k, v);
                }
            }
        }

        String rawSrc = firstNonBlank(merged.get("src"), merged.get("url"), linkUrl);
        String safeSrc = sanitizeCastUrl(rawSrc);
        String title = firstNonBlank(node.getTitle(), merged.get("title"), linkText, extractCastFileName(safeSrc), "Asciinema Recording");
        String idleTimeLimit = firstNonBlank(
                merged.get("idletimelimit"),
                merged.get("idle-time-limit"),
                merged.get("idle_time_limit"),
                "2");
        String speed = firstNonBlank(merged.get("speed"), "1");
        String pauseOnMarkers = firstNonBlank(
                merged.get("pauseonmarkers"),
                merged.get("pause-on-markers"),
                merged.get("pause_on_markers"),
                "true");
        String controls = firstNonBlank(merged.get("controls"), "true");
        String autoPlay = firstNonBlank(
                merged.get("autoplay"),
                merged.get("auto-play"),
                merged.get("auto_play"),
                "false");
        String loop = firstNonBlank(merged.get("loop"), "false");
        String cols = firstNonBlank(merged.get("cols"), merged.get("width"));
        String rows = firstNonBlank(merged.get("rows"), merged.get("height"));
        String markers = firstNonBlank(merged.get("markers"));
        String theme = firstNonBlank(merged.get("theme"), "auto");

        html.attr("class", "asciinema-player-card not-prose")
                .attr("data-name", "asciinema-player")
                .attr("data-block-id", blockId)
                .attr("data-asciinema-player", "true")
                .attr("data-title", title)
                .attr("data-idle-time-limit", idleTimeLimit)
                .attr("data-speed", speed)
                .attr("data-pause-on-markers", pauseOnMarkers)
                .attr("data-controls", controls)
                .attr("data-auto-play", autoPlay)
                .attr("data-loop", loop)
                .attr("data-theme", theme);

        if (safeSrc != null) {
            html.attr("data-src", safeSrc);
        }
        if (cols != null) {
            html.attr("data-cols", cols);
        }
        if (rows != null) {
            html.attr("data-rows", rows);
        }
        if (markers != null) {
            html.attr("data-markers", markers);
        }

        html.withAttr().tag("div");
        if (safeSrc != null) {
            html.attr("href", safeSrc)
                    .attr("class", "asciinema-fallback-link")
                    .attr("target", "_blank")
                    .attr("rel", "noopener noreferrer")
                    .withAttr()
                    .tag("a");
            html.text(title);
            html.tag("/a");
        } else {
            html.attr("class", "asciinema-fallback-empty").withAttr().tag("span");
            html.text("未配置有效的 .cast 录屏地址");
            html.tag("/span");
        }
        html.tag("/div");
    }

    private com.vladsch.flexmark.ast.Link findFirstLink(com.vladsch.flexmark.util.ast.Node root) {
        for (com.vladsch.flexmark.util.ast.Node child = root.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof com.vladsch.flexmark.ast.Link link) {
                return link;
            }
            com.vladsch.flexmark.ast.Link nested = findFirstLink(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String sanitizeCastUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return trimmed;
        }
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return trimmed;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static String extractCastFileName(String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        String clean = src;
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        int slash = clean.lastIndexOf('/');
        String leaf = slash >= 0 ? clean.substring(slash + 1) : clean;
        return leaf.isBlank() ? null : leaf;
    }

    private com.biliwind.blog.model.BlogRegion getCurrentRegion() {
        try {
            var container = io.quarkus.arc.Arc.container();
            if (container != null) {
                var instance = container.instance(com.biliwind.blog.context.RegionContext.class);
                if (instance != null && instance.isAvailable()) {
                    return instance.get().getCurrentRegion();
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return com.biliwind.blog.model.BlogRegion.GLOBAL;
    }

    private void renderBlockQuote(com.vladsch.flexmark.ast.BlockQuote node, NodeRendererContext context, HtmlWriter html) {
        // Render blockquote with old 'tip' style
        html.attr("class", "md-region md-region-tip")
            .withAttr()
                .tag("blockquote");
        
        // Inject icon container
        html.attr("class", "md-region-icon")
            .withAttr()
            .tag("div")
            .tag("/div");

        html.attr("class", "md-region-content")
            .withAttr()
            .tag("div");
        context.renderChildren(node);
        html.tag("/div");

        html.tag("/blockquote");
    }

    private void renderHighlight(MdNodes.HighlightNode node, NodeRendererContext context, HtmlWriter html) {
        html.tag("mark");
        context.renderChildren(node);
        html.tag("/mark");
    }

    private void renderKeyboard(MdNodes.KeyboardNode node, NodeRendererContext context, HtmlWriter html) {
        html.tag("kbd");
        context.renderChildren(node);
        html.tag("/kbd");
    }

    private void renderProgress(MdNodes.ProgressNode node, NodeRendererContext context, HtmlWriter html) {
        html.attr("class", "md-progress")
            .attr("value", String.valueOf(node.getValue()))
            .attr("max", "100")
            .withAttr()
            .tag("progress");
        html.tag("/progress");
    }

    private void renderStatusBadge(MdNodes.StatusBadgeNode node, NodeRendererContext context, HtmlWriter html) {
        html.attr("class", "badge badge-" + node.getStatus().toLowerCase())
            .withAttr()
            .tag("span");
        html.text(node.getLabel());
        html.tag("/span");
    }

    private void renderCallout(MdNodes.CalloutBlock node, NodeRendererContext context, HtmlWriter html) {
        String type = node.getType().toLowerCase();
        String label;
        switch (type) {
            case "danger": label = "DANGER"; break;
            case "warning": label = "WARNING"; break;
            case "question": label = "QUESTION"; break;
            default: label = "INFO"; break;
        }

        html.attr("class", "md-callout md-callout-" + type)
            .withAttr()
            .tag("div");
        
        // Header Bar
        html.attr("class", "md-callout-header")
            .withAttr()
            .tag("div");
        
        html.attr("class", "md-callout-icon")
            .withAttr()
            .tag("div")
            .tag("/div");
            
        html.tag("span");
        html.text(label);
        html.tag("/span");
        
        html.tag("/div");

        // Content Area
        html.attr("class", "md-callout-content")
            .withAttr()
            .tag("div");
        html.text(node.getContent().toString());
        html.tag("/div");
        
        html.tag("/div");
    }

    public static class Factory implements NodeRendererFactory {
        @NotNull
        @Override
        public NodeRenderer apply(@NotNull DataHolder options) {
            return new MdProtocolNodeRenderer();
        }
    }
}
