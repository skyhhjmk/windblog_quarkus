package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiResult;
import com.biliwind.blog.service.ai.CodexCreatorHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * API-provider topic discovery. Used by the Flutter topics page when the admin
 * switches from Codex Creator mode to an AI provider (including polling
 * groups). Generated topics are imported into the shared Codex Creator topic
 * pool so review and draft generation keep working unchanged.
 */
@Path("/api/admin/ai/topics/discover")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminAiTopicDiscoveryController {

    private static final String SYSTEM_PROMPT = "You are WindBlog's evidence-led technical topic editor. "
            + "Return only valid JSON and never invent URLs or citations.";

    @Inject
    AiManager aiManager;

    @Inject
    CodexCreatorHttpClient codexClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @POST
    @Operation(summary = "使用 AI 提供商或轮询组生成技术文章话题")
    public Response discover(DiscoveryRequest request) {
        int maxTopics = request == null || request.maxTopics() == null
                ? 10 : Math.max(1, Math.min(50, request.maxTopics()));
        List<Seed> seeds = request == null ? List.of() : safeSeeds(request.seeds());
        if (seeds.isEmpty()) {
            seeds = loadCodexSeeds();
        }
        if (seeds.isEmpty()) {
            throw new BadRequestException("话题种子为空，请先配置主题查询种子");
        }
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "zh-CN" : request.language().trim();

        AiResult result;
        try {
            result = aiManager.complete(request == null ? null : request.providerId(),
                            SYSTEM_PROMPT, buildPrompt(seeds, maxTopics, language))
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("话题生成失败: " + safeMessage(cause));
        }
        String text = result == null || result.contents == null ? "" : result.contents.get("text");
        List<Map<String, Object>> topics = parseTopics(text, seeds, result);
        if (topics.isEmpty()) {
            throw new BadRequestException("AI 未返回可用话题");
        }

        try {
            JsonNode imported = codexClient.topicCommand("topics.import",
                            Map.of("topics", topics),
                            "windblog-admin:" + actorId(), UUID.randomUUID().toString())
                    .toCompletableFuture()
                    .join();
            JsonNode data = imported.path("data");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("created", data.path("created").asInt(0));
            body.put("updated", data.path("updated").asInt(0));
            body.put("total", data.path("total").asInt(topics.size()));
            body.put("provider", result.provider);
            body.put("modelId", result.modelId);
            body.put("topics", topics);
            return Response.ok(body).build();
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("success", false,
                            "message", "话题已生成但写入 Codex Creator 失败: " + safeMessage(cause),
                            "topics", topics))
                    .build();
        }
    }

    private String buildPrompt(List<Seed> seeds, int maxTopics, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 WindBlog 的技术选题编辑。根据给定的查询种子，提出最多 ")
                .append(maxTopics).append(" 个不重复、可写成实质技术文章的中文话题。\n")
                .append("要求：\n")
                .append("1. 每个话题必须有明确的问题、冲突或决策，不要写泛泛的趋势总结、榜单或营销文。\n")
                .append("2. 标题使用自然、具体的中文博客标题，避免“一文读懂”“全面解析”“深度剖析”等套路。\n")
                .append("3. rationale 用 2-3 句说明具体场景、读者能获得什么，以及为什么值得现在写。\n")
                .append("4. 每个话题分配一个 seedId（必须来自给定种子），并给出 3-8 个关键词。\n")
                .append("5. recommendation 只能是 WRITE、MONITOR、IGNORE、MANUAL 之一。\n")
                .append("6. 不要编造 URL、数据或引用；没有检索能力时只输出选题，不输出 sources。\n")
                .append("7. 只输出 JSON，不要 Markdown 代码围栏或解释，格式：\n")
                .append("{\"topics\":[{\"seedId\":1,\"title\":\"...\",\"rationale\":\"...\",")
                .append("\"keywords\":[\"...\"],\"recommendation\":\"WRITE\"}]}\n")
                .append("文章语言：").append(language).append("\n种子：\n");
        for (Seed seed : seeds) {
            prompt.append("[").append(seed.id()).append(" | ").append(seed.name()).append(" | ")
                    .append(seed.query()).append(" | ").append(seed.language()).append("]\n");
        }
        return prompt.toString();
    }

    private List<Map<String, Object>> parseTopics(String text, List<Seed> seeds, AiResult result) {
        JsonNode root = readJson(text);
        JsonNode values = root == null ? null : root.isArray() ? root : root.get("topics");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        Set<Long> seedIds = new LinkedHashSet<>();
        for (Seed seed : seeds) {
            seedIds.add(seed.id());
        }
        Long fallbackSeed = seeds.get(0).id();
        List<Map<String, Object>> topics = new ArrayList<>();
        for (JsonNode value : values) {
            String title = value.path("title").asText("").trim();
            if (title.isBlank()) {
                continue;
            }
            if (title.length() > 160) {
                title = title.substring(0, 160);
            }
            Long seedId = value.path("seedId").canConvertToLong()
                    ? value.path("seedId").asLong() : fallbackSeed;
            if (!seedIds.contains(seedId)) {
                seedId = fallbackSeed;
            }
            String recommendation = value.path("recommendation").asText("MANUAL")
                    .trim().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("WRITE", "MONITOR", "IGNORE", "MANUAL").contains(recommendation)) {
                recommendation = "MANUAL";
            }
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("seedId", seedId);
            topic.put("title", title);
            topic.put("rationale", truncate(value.path("rationale").asText(""), 2000));
            topic.put("recommendation", recommendation);
            List<String> keywords = new ArrayList<>();
            JsonNode keywordNode = value.get("keywords");
            if (keywordNode != null && keywordNode.isArray()) {
                keywordNode.forEach(item -> {
                    if (item.isTextual() && keywords.size() < 12) {
                        keywords.add(truncate(item.asText(), 80));
                    }
                });
            }
            topic.put("keywords", keywords);
            topic.put("provider", result == null || result.provider == null ? "AI_MANAGER" : result.provider);
            topic.put("model", result == null || result.modelId == null ? "" : result.modelId);
            topic.put("language", "zh-CN");
            topics.add(topic);
        }
        return topics;
    }

    private JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        int start = objectStart < 0 ? arrayStart : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
        if (start > 0) {
            value = value.substring(start);
        }
        int lastObject = value.lastIndexOf('}');
        int lastArray = value.lastIndexOf(']');
        int end = Math.max(lastObject, lastArray);
        if (end >= 0 && end < value.length() - 1) {
            value = value.substring(0, end + 1);
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Seed> loadCodexSeeds() {
        try {
            JsonNode response = codexClient.topicCommand("seeds.list", Map.of(),
                            "windblog-admin:" + actorId(), UUID.randomUUID().toString())
                    .toCompletableFuture().join();
            JsonNode items = response.path("data").path("items");
            List<Seed> seeds = new ArrayList<>();
            for (JsonNode item : items) {
                if (!item.path("enabled").asBoolean(true)) {
                    continue;
                }
                long id = item.path("id").asLong(0);
                if (id <= 0) {
                    continue;
                }
                seeds.add(new Seed(id, item.path("name").asText(""), item.path("query").asText(""),
                        item.path("language").asText("zh-CN")));
            }
            return seeds;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<Seed> safeSeeds(List<Seed> seeds) {
        List<Seed> result = new ArrayList<>();
        if (seeds == null) {
            return result;
        }
        for (Seed seed : seeds) {
            if (seed != null && seed.id() != null && seed.id() > 0
                    && seed.query() != null && !seed.query().isBlank()) {
                result.add(seed);
            }
        }
        return result;
    }

    private Long actorId() {
        Long userId = adminRequestContext.getUserId();
        return userId == null ? 0L : userId;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record DiscoveryRequest(Long providerId, Integer maxTopics, String language, List<Seed> seeds) {
    }

    public record Seed(Long id, String name, String query, String language) {
    }
}
