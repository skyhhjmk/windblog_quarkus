package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarTemplateDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultWidgetsAreReturnedWhenConfigIsNull() {
        List<SidebarTemplateData.SidebarWidgetView> homeWidgets = SidebarTemplateData.resolveWidgets(null, "home");
        assertEquals(List.of("stats", "recent_posts", "categories", "tags"),
                homeWidgets.stream().map(SidebarTemplateData.SidebarWidgetView::type).toList());

        List<SidebarTemplateData.SidebarWidgetView> postWidgets = SidebarTemplateData.resolveWidgets(null, "post");
        assertEquals(List.of(
                        "post_access",
                        "tutorial_tracker",
                        "toc",
                        "block_manager",
                        "post_attachments",
                        "related_store",
                        "recent_posts",
                        "tags"
                ),
                postWidgets.stream().map(SidebarTemplateData.SidebarWidgetView::type).toList());
    }

    @Test
    void customOrderAndParametersAndCustomHtmlWidgetsAreResolved() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode areas = root.putObject("areas");
        ArrayNode home = areas.putArray("home");

        home.addObject()
                .put("id", "custom-1")
                .put("type", "custom_html")
                .put("enabled", true)
                .put("title", "站长公告")
                .put("content", "<p>欢迎访问 WindBlog</p>")
                .put("showCardWrapper", true);

        home.addObject()
                .put("id", "recent-1")
                .put("type", "recent_posts")
                .put("enabled", true)
                .put("title", "近期更新")
                .put("limit", 8)
                .put("showDate", false);

        home.addObject()
                .put("id", "disabled-stats")
                .put("type", "stats")
                .put("enabled", false);

        // Post-only widgets placed in a non-post area should be ignored
        home.addObject()
                .put("id", "ignored-toc")
                .put("type", "toc")
                .put("enabled", true);

        List<SidebarTemplateData.SidebarWidgetView> widgets = SidebarTemplateData.resolveWidgets(root, "home");
        assertEquals(2, widgets.size());

        SidebarTemplateData.SidebarWidgetView first = widgets.get(0);
        assertEquals("custom_html", first.type());
        assertEquals("站长公告", first.title());
        assertEquals("<p>欢迎访问 WindBlog</p>", first.content());
        assertTrue(first.showCardWrapper());

        SidebarTemplateData.SidebarWidgetView second = widgets.get(1);
        assertEquals("recent_posts", second.type());
        assertEquals("近期更新", second.title());
        assertEquals(8, second.limit());
        assertFalse(second.showDate());
    }

    @Test
    void emptyAreaArrayRendersNoWidgetsInsteadOfFallback() {
        ObjectNode root = SidebarTemplateData.buildDefaultSidebarLayoutNode(mapper);
        ((ObjectNode) root.get("areas")).putArray("link");

        List<SidebarTemplateData.SidebarWidgetView> linkWidgets = SidebarTemplateData.resolveWidgets(root, "link");
        assertTrue(linkWidgets.isEmpty());
    }
}
