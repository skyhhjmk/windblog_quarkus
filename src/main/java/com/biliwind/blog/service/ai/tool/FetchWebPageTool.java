package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.service.ai.AiTool;
import com.biliwind.blog.service.ai.AiToolContext;
import com.biliwind.blog.service.ai.AiToolDefinition;
import com.biliwind.blog.service.security.SafeExternalHttpService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches a public web page via {@link SafeExternalHttpService} and extracts
 * readable text and candidate image URLs.
 */
@ApplicationScoped
public class FetchWebPageTool implements AiTool {

    private static final int MAX_TEXT_LENGTH = 8000;

    private static final AiToolDefinition DEFINITION = new AiToolDefinition(
            "fetch_web_page",
            "Fetch a public web page URL and extract its title, main text content, and image URLs.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "url", Map.of("type", "string", "description", "Public http/https URL to fetch"),
                            "includeImages", Map.of("type", "boolean", "description", "Whether to extract image URLs (default true)")
                    ),
                    "required", List.of("url")
            )
    );

    @Inject
    SafeExternalHttpService safeExternalHttpService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public AiToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String execute(JsonNode arguments, AiToolContext context) throws Exception {
        if (context != null && !context.allowNetwork()) {
            return objectMapper.writeValueAsString(Map.of("error", "当前上下文不允许网络抓取"));
        }
        String url = arguments == null ? "" : arguments.path("url").asText("").trim();
        if (url.isBlank()) {
            return objectMapper.writeValueAsString(Map.of("error", "url 不能为空"));
        }
        boolean includeImages = arguments == null || !arguments.has("includeImages")
                || arguments.path("includeImages").asBoolean(true);

        SafeExternalHttpService.ExternalHttpResponse response = safeExternalHttpService.get(
                url, "WindBlog-AI-Researcher/1.0", 2 * 1024 * 1024);
        if (response.statusCode() >= 400) {
            return objectMapper.writeValueAsString(Map.of(
                    "error", "HTTP " + response.statusCode(),
                    "url", url
            ));
        }

        String contentType = response.headerValue("Content-Type");
        String body = response.bodyAsText();
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        if (lowerType.contains("text/html") || body.trim().startsWith("<")) {
            Document doc = Jsoup.parse(body, url);
            result.put("title", doc.title());
            if (includeImages) {
                List<Map<String, String>> images = new ArrayList<>();
                for (Element img : doc.select("img[src]")) {
                    if (images.size() >= 10) break;
                    String absSrc = img.absUrl("src").trim();
                    if (absSrc.isBlank() || absSrc.startsWith("data:") || absSrc.endsWith(".svg")) {
                        continue;
                    }
                    images.add(Map.of("url", absSrc, "alt", img.attr("alt").trim()));
                }
                result.put("images", images);
            }
            doc.select("script, style, noscript, svg, nav, footer").remove();
            Element main = doc.selectFirst("article, main");
            String text = main != null ? main.text() : (doc.body() != null ? doc.body().text() : doc.text());
            result.put("content", truncate(text, MAX_TEXT_LENGTH));
        } else {
            result.put("content", truncate(body, MAX_TEXT_LENGTH));
        }
        return objectMapper.writeValueAsString(result);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
    }
}
