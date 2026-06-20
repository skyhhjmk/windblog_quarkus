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

        html.attr("class", "custom-block block-" + name)
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
