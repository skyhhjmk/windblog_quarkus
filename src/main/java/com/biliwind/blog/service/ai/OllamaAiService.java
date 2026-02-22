package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProvider;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class OllamaAiService implements AiService {

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
        AiProviderConfig config = configService.getByProvider(AiProvider.OLLAMA)
                .filter(this::isConfigReady)
                .orElseThrow(() -> new RuntimeException("Ollama 配置不可用"));

        return CompletableFuture.supplyAsync(() -> summarizeWithConfig(config, content));
    }

    private Map<String, String> summarizeWithConfig(AiProviderConfig config,
                                                    Map<String, String> content) {
        Map<String, String> summaries = new HashMap<>();
        for (Map.Entry<String, String> entry : content.entrySet()) {
            summaries.put(entry.getKey(), callGenerate(config, entry.getKey(), entry.getValue()));
        }
        return summaries;
    }

    private String callGenerate(AiProviderConfig config, String lang, String text) {
        try {
            String prompt = buildPrompt(lang, text);
            URI uri = resolveUri(config);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", chooseModel(config, "ollama/llama3"));
            payload.put("prompt", Map.of("type", "text", "text", prompt));
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 256);
            payload.put("stream", false);

            String body = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            attachApiKey(builder, config);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 400) {
                throw new RuntimeException("Ollama 调用失败: " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String textResult = extractText(root);
            return textResult.isBlank() ? text : textResult;
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException("调用 Ollama 失败", ex);
        }
    }

    private void attachApiKey(HttpRequest.Builder builder, AiProviderConfig config) {
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey.trim());
        }
    }

    private URI resolveUri(AiProviderConfig config) {
        String base = config.endpoint.trim();
        if (base.endsWith("/api/generate")) {
            return URI.create(base);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/api/generate");
    }

    private boolean isConfigReady(AiProviderConfig config) {
        return config.enabled && config.endpoint != null && !config.endpoint.isBlank();
    }

    private String buildPrompt(String lang, String text) {
        String languageHint = lang == null || lang.isBlank() ? "全文" : lang;
        return String.format("请用 %s 简洁总结以下内容，控制在 120 字以内：\\n%s",
                languageHint, text == null ? "" : text);
    }

    private String chooseModel(AiProviderConfig config, String fallback) {
        return config.model == null || config.model.isBlank()
                ? fallback
                : config.model;
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        if (node.has("choices") && node.get("choices").isArray()) {
            for (JsonNode choice : node.get("choices")) {
                String text = extractText(choice);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }

        if (node.has("text")) {
            return node.get("text").asText("");
        }

        if (node.has("message")) {
            JsonNode message = node.get("message");
            if (message.has("content")) {
                return message.get("content").asText("");
            }
        }

        if (node.has("output") && node.get("output").isArray()) {
            for (JsonNode item : node.get("output")) {
                if (item.has("content")) {
                    JsonNode content = item.get("content");
                    if (content.isArray()) {
                        for (JsonNode line : content) {
                            if (line.has("text")) {
                                String text = line.get("text").asText("");
                                if (!text.isBlank()) {
                                    return text;
                                }
                            }
                        }
                    }
                }
            }
        }

        return node.asText("");
    }

    @Override
    public CompletionStage<Boolean> moderate(String content) {
        if (content == null) {
            return CompletableFuture.completedFuture(true);
        }
        boolean safe = !content.toLowerCase().contains("spam");
        return CompletableFuture.completedFuture(safe);
    }

    @Override
    public boolean isAvailable() {
        return configService.getByProvider(AiProvider.OLLAMA)
                .filter(this::isConfigReady)
                .isPresent();
    }

    @Override
    public int getPriority() {
        return 0;
    }
}
