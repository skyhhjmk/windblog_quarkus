package com.biliwind.blog.service.ai;

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
 * Ollama AI 摘要服务
 * 使用 sendAsync 全程异步，不会阻塞 RabbitMQ 消费者线程
 */
@ApplicationScoped
public class OllamaAiService implements AiService {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "OLLAMA".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<Map<String, String>> summarize(AiProviderConfig config, Map<String, String> content) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("Ollama 配置不可用"));
        }

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
     * 异步调用 Ollama generate API，不阻塞当前线程
     */
    private CompletableFuture<String> callApiAsync(AiProviderConfig config, String lang, String text) {
        try {
            String prompt = buildPrompt(lang, text);
            URI uri = resolveUri(config, "/api/generate");

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", chooseModel(config, "llama3"));
            payload.put("prompt", prompt);
            payload.put("stream", false);

            String bodyJson = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("Ollama 调用失败，code=" + response.statusCode());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            String extracted = extractTextFromResponse(root);
                            return extracted.isBlank() ? text : extracted;
                        } catch (Exception parseEx) {
                            throw new RuntimeException("解析 Ollama 响应失败", parseEx);
                        }
                    });
        } catch (Exception buildEx) {
            return CompletableFuture.failedFuture(new RuntimeException("构建 Ollama 请求失败", buildEx));
        }
    }

    private URI resolveUri(AiProviderConfig config, String path) {
        String base = config.endpoint.trim();
        if (base.endsWith(path)) {
            return URI.create(base);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private boolean isConfigReady(AiProviderConfig config) {
        return config != null && config.enabled && config.endpoint != null && !config.endpoint.isBlank();
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

        // Ollama /api/generate 返回 {"response": "..."}
        if (node.has("response")) {
            return node.get("response").asText("");
        }

        // 兼容 /api/chat 格式
        if (node.has("message") && node.get("message").has("content")) {
            return node.get("message").get("content").asText("");
        }

        // 兼容 OpenAI 格式
        if (node.has("choices") && node.get("choices").isArray()) {
            for (JsonNode choice : node.get("choices")) {
                JsonNode message = choice.get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText("");
                }
            }
        }

        return "";
    }

    @Override
    public CompletionStage<Boolean> moderate(AiProviderConfig config, String content) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public io.smallrye.mutiny.Multi<String> testStream(AiProviderConfig config, com.biliwind.blog.controller.api.admin.dto.AiTestRequest request) {
        return io.smallrye.mutiny.Multi.createFrom().emitter(emitter -> {
            try {
                URI uri = resolveUri(config, "/api/chat");
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", chooseModel(config, "llama3"));
                payload.put("stream", true);

                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }

                if (request.imageUrls() != null && !request.imageUrls().isEmpty()) {
                    // Ollama image support in chat API (images: base64 list). But here we just pass the URL and hope the user knows ollama text UI.
                    // For simplicity, we just pass text as user.
                    messages.add(Map.of("role", "user", "content", request.prompt() + "\n[Images: " + String.join(", ", request.imageUrls()) + "]"));
                } else {
                    messages.add(Map.of("role", "user", "content", request.prompt()));
                }
                payload.put("messages", messages);

                String bodyJson = objectMapper.writeValueAsString(payload);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                        .build();

                httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
                        .whenComplete((res, err) -> {
                            if (err != null) {
                                emitter.fail(err);
                                return;
                            }
                            if (res.statusCode() >= 400) {
                                emitter.fail(new RuntimeException("API 调用失败, code=" + res.statusCode()));
                                return;
                            }
                            try (java.util.stream.Stream<String> lines = res.body()) {
                                lines.forEach(line -> {
                                    if (line.isBlank()) return;
                                    try {
                                        JsonNode root = objectMapper.readTree(line);
                                        if (root.has("message") && root.get("message").has("content")) {
                                            String content = root.get("message").get("content").asText("");
                                            if (!content.isEmpty()) {
                                                emitter.emit("{\"type\":\"content\",\"content\":" + objectMapper.writeValueAsString(content) + "}");
                                            }
                                        }
                                    } catch (Exception ex) {
                                        // Parse error
                                    }
                                });
                            }
                            emitter.complete();
                        });
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }
}
