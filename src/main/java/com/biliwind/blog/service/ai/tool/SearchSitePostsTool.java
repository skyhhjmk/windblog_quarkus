package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.service.ai.AiTool;
import com.biliwind.blog.service.ai.AiToolContext;
import com.biliwind.blog.service.ai.AiToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Searches published WindBlog articles so AI drafts can reference accurate internal links.
 */
@ApplicationScoped
public class SearchSitePostsTool implements AiTool {

    private static final AiToolDefinition DEFINITION = new AiToolDefinition(
            "search_site_posts",
            "Search existing published articles on WindBlog by keyword to find accurate internal links.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "Search keyword"),
                            "limit", Map.of("type", "integer", "description", "Maximum results (1-10, default 5)")
                    ),
                    "required", List.of("query")
            )
    );

    @Inject
    ObjectMapper objectMapper;

    @Override
    public AiToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String execute(JsonNode arguments, AiToolContext context) throws Exception {
        String query = arguments == null ? "" : arguments.path("query").asText("").trim();
        int limit = arguments == null ? 5 : Math.max(1, Math.min(10, arguments.path("limit").asInt(5)));
        if (query.isBlank()) {
            return objectMapper.writeValueAsString(Map.of("results", List.of()));
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = QuarkusTransaction.requiringNew().call(() -> {
            List<Post> posts = Post.<Post>find(
                    "status = ?1 and visibility = 0 order by publishedAt desc nulls last, id desc",
                    PostStatus.PUBLISHED).page(0, 60).list();
            List<Map<String, Object>> list = new ArrayList<>();
            for (Post post : posts) {
                if (list.size() >= limit) break;
                String titleText = post.title == null ? "" : String.join(" ", post.title.values());
                String summaryText = post.summary == null ? "" : String.join(" ", post.summary.values());
                String haystack = (titleText + " " + summaryText + " " + (post.slug == null ? "" : post.slug))
                        .toLowerCase(Locale.ROOT);
                if (haystack.contains(lowerQuery)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", post.id);
                    item.put("slug", post.slug);
                    item.put("url", "/posts/" + post.slug);
                    item.put("title", post.title == null ? Map.of() : post.title);
                    list.add(item);
                }
            }
            return list;
        });
        return objectMapper.writeValueAsString(Map.of("query", query, "count", matches.size(), "results", matches));
    }
}
