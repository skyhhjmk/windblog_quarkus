package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProvider;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * ChatGLM AI 摘要服务
 * 使用 sendAsync 全程异步，不会阻塞 RabbitMQ 消费者线程
 */
@ApplicationScoped
public class ChatGlmAiService implements AiService {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Inject
    AiProviderConfigService configService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public CompletionStage<Map<String, String>> summarize(Map<String, String> content) {
        AiProviderConfig config = configService.getByProvider(AiProvider.CHATGLM)
                .filter(this::isConfigReady)
                .orElse(null);

        if (config == null) {
            return CompletableFuture.failedFuture(new RuntimeException("ChatGLM 配置不可用"));
        }

        // 逐语言分别异步调用，最后合并结果
        List<CompletableFuture<Map.Entry<String, String>>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : content.entrySet()) {
            String lang = entry.getKey();
            String text = entry.getValue();
            CompletableFuture<Map.Entry<String, String>> future = callApiAsync(config, lang, text)
                    .thenApply(summary -> Map.entry(lang, summary));
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    Map<String, String> summaries = new HashMap<>();
                    for (CompletableFuture<Map.Entry<String, String>> future : futures) {
                        Map.Entry<String, String> entry = future.join();
                        summaries.put(entry.getKey(), entry.getValue());
                    }
                    return summaries;
                });
    }

    /**
     * 异步调用 ChatGLM API，不阻塞当前线程
     */
    private CompletableFuture<String> callApiAsync(AiProviderConfig config, String lang, String text) {
        try {
            String prompt = buildPrompt(lang, text);
            URI uri = resolveUri(config);

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", chooseModel(config, "chatglm2"));

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            payload.put("messages", List.of(message));
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 256);

            String bodyJson = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

            attachApiKeyHeader(builder, config);

            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("ChatGLM 调用失败，code=" + response.statusCode());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            String extracted = extractTextFromResponse(root);
                            return extracted.isBlank() ? text : extracted;
                        } catch (Exception parseEx) {
                            throw new RuntimeException("解析 ChatGLM 响应失败", parseEx);
                        }
                    });
        } catch (Exception buildEx) {
            return CompletableFuture.failedFuture(new RuntimeException("构建 ChatGLM 请求失败", buildEx));
        }
    }

    private void attachApiKeyHeader(HttpRequest.Builder builder, AiProviderConfig config) {
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey.trim());
        }
    }

    private URI resolveUri(AiProviderConfig config) {
        String base = config.endpoint.trim();
        if (base.endsWith("/api/chat/completions") || base.endsWith("/v1/chat/completions")) {
            return URI.create(base);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/v1/chat/completions");
    }

    private boolean isConfigReady(AiProviderConfig config) {
        return config.enabled && config.endpoint != null && !config.endpoint.isBlank();
    }

    private String chooseModel(AiProviderConfig config, String fallback) {
        if (config.model == null || config.model.isBlank()) {
            return fallback;
        }
        return config.model;
    }

    private String buildPrompt(String lang, String text) {
        String languageHint = (lang == null || lang.isBlank()) ? "中文" : lang;
        return "请用" + languageHint + "简洁地总结以下内容，控制在 120 字以内：\n" + (text == null ? "" : text);
    }

    private String extractTextFromResponse(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        if (node.has("choices") && node.get("choices").isArray()) {
            for (JsonNode choice : node.get("choices")) {
                JsonNode message = choice.get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText("");
                }
            }
        }

        if (node.has("message") && node.get("message").has("content")) {
            return node.get("message").get("content").asText("");
        }

        if (node.has("content") && node.get("content").isTextual()) {
            return node.get("content").asText("");
        }

        return "";
    }

    @Override
    public CompletionStage<Boolean> moderate(String content) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public boolean isAvailable() {
        return configService.getByProvider(AiProvider.CHATGLM)
                .filter(this::isConfigReady)
                .isPresent();
    }

    @Override
    public int getPriority() {
        return 1;
    }
}
