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

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in web search tool for AI article drafting. Supports SearXNG, Tavily,
 * Serper, and zero-config DuckDuckGo HTML fallback, all validated through
 * {@link SafeExternalHttpService}.
 */
@ApplicationScoped
public class WebSearchTool implements AiTool {

    private static final AiToolDefinition DEFINITION = new AiToolDefinition(
            "web_search",
            "Search the web for up-to-date technical facts, documentation, benchmarks, or image sources.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "Search query keywords"),
                            "maxResults", Map.of("type", "integer", "description", "Maximum number of results (1-10, default 5)")
                    ),
                    "required", List.of("query")
            )
    );

    private final HttpClient postClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Inject
    AiMcpSettingsService mcpSettingsService;

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
            return objectMapper.writeValueAsString(Map.of("error", "当前上下文不允许网络搜索"));
        }
        String query = arguments == null ? "" : arguments.path("query").asText("").trim();
        if (query.isBlank()) {
            return objectMapper.writeValueAsString(Map.of("error", "query 不能为空"));
        }
        int maxResults = arguments == null ? 5 : Math.max(1, Math.min(10, arguments.path("maxResults").asInt(5)));
        AiMcpSettingsService.McpSettings settings = mcpSettingsService.current();
        String provider = settings.webSearchProvider();

        List<Map<String, String>> results;
        if ("SEARXNG".equals(provider) || ("AUTO".equals(provider) && !settings.searxngUrl().isBlank())) {
            results = searchSearxng(settings.searxngUrl(), query, maxResults);
        } else if ("TAVILY".equals(provider) || ("AUTO".equals(provider) && !settings.tavilyApiKey().isBlank())) {
            results = searchTavily(settings.tavilyApiKey(), query, maxResults);
        } else if ("SERPER".equals(provider) || ("AUTO".equals(provider) && !settings.serperApiKey().isBlank())) {
            results = searchSerper(settings.serperApiKey(), query, maxResults);
        } else {
            results = searchDuckDuckGo(query, maxResults);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("count", results.size());
        payload.put("results", results);
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, String>> searchSearxng(String baseUrl, String query, int maxResults) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置 SearXNG 地址");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = normalized + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        SafeExternalHttpService.ExternalHttpResponse response = safeExternalHttpService.get(
                url, "WindBlog-AI-Search/1.0", 2 * 1024 * 1024);
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("SearXNG HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.bodyAsText());
        List<Map<String, String>> results = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            if (results.size() >= maxResults) break;
            String itemUrl = item.path("url").asText("").trim();
            String title = item.path("title").asText("").trim();
            if (itemUrl.isBlank() || title.isBlank()) continue;
            results.add(Map.of(
                    "title", title,
                    "url", itemUrl,
                    "snippet", truncate(item.path("content").asText(""), 400)
            ));
        }
        return results;
    }

    private List<Map<String, String>> searchTavily(String apiKey, String query, int maxResults) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 Tavily API Key");
        }
        URI uri = safeExternalHttpService.validatePublicHttpUri("https://api.tavily.com/search");
        String body = objectMapper.writeValueAsString(Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", maxResults,
                "search_depth", "basic"
        ));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = postClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Tavily HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<Map<String, String>> results = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            if (results.size() >= maxResults) break;
            String itemUrl = item.path("url").asText("").trim();
            String title = item.path("title").asText("").trim();
            if (itemUrl.isBlank() || title.isBlank()) continue;
            results.add(Map.of(
                    "title", title,
                    "url", itemUrl,
                    "snippet", truncate(item.path("content").asText(""), 400)
            ));
        }
        return results;
    }

    private List<Map<String, String>> searchSerper(String apiKey, String query, int maxResults) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 Serper API Key");
        }
        URI uri = safeExternalHttpService.validatePublicHttpUri("https://google.serper.dev/search");
        String body = objectMapper.writeValueAsString(Map.of("q", query, "num", maxResults));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = postClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Serper HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<Map<String, String>> results = new ArrayList<>();
        for (JsonNode item : root.path("organic")) {
            if (results.size() >= maxResults) break;
            String itemUrl = item.path("link").asText("").trim();
            String title = item.path("title").asText("").trim();
            if (itemUrl.isBlank() || title.isBlank()) continue;
            results.add(Map.of(
                    "title", title,
                    "url", itemUrl,
                    "snippet", truncate(item.path("snippet").asText(""), 400)
            ));
        }
        return results;
    }

    private List<Map<String, String>> searchDuckDuckGo(String query, int maxResults) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        SafeExternalHttpService.ExternalHttpResponse response = safeExternalHttpService.get(
                url, "Mozilla/5.0 (compatible; WindBlog-AI-Search/1.0)", 2 * 1024 * 1024);
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("DuckDuckGo HTTP " + response.statusCode());
        }
        Document doc = Jsoup.parse(response.bodyAsText(), url);
        List<Map<String, String>> results = new ArrayList<>();
        for (Element el : doc.select(".result")) {
            if (results.size() >= maxResults) break;
            Element link = el.selectFirst("a.result__a");
            if (link == null) continue;
            String title = link.text().trim();
            String href = unwrapDuckDuckGoHref(link.absUrl("href").isBlank()
                    ? link.attr("href") : link.absUrl("href"));
            if (title.isBlank() || href.isBlank()) continue;
            Element snippetEl = el.selectFirst(".result__snippet");
            String snippet = snippetEl == null ? "" : snippetEl.text().trim();
            results.add(Map.of("title", title, "url", href, "snippet", truncate(snippet, 400)));
        }
        return results;
    }

    private static String unwrapDuckDuckGoHref(String href) {
        if (href == null || href.isBlank()) return "";
        int idx = href.indexOf("uddg=");
        if (idx >= 0) {
            String raw = href.substring(idx + 5);
            int amp = raw.indexOf('&');
            if (amp >= 0) raw = raw.substring(0, amp);
            try {
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return href;
            }
        }
        return href;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen) + "...";
    }
}
