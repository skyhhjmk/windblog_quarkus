package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(OllamaAiService.class);

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
    public CompletionStage<AiResult> summarize(AiProviderConfig config, Map<String, String> content) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("Ollama 配置不可用"));
        }

        List<CompletableFuture<AiResult>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : content.entrySet()) {
            String lang = entry.getKey();
            String text = entry.getValue();
            futures.add(callApiAsync(config, lang, text));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    AiResult finalResult = new AiResult();
                    for (CompletableFuture<AiResult> future : futures) {
                        try {
                            AiResult res = future.join();
                            for (Map.Entry<String, String> entry : res.contents.entrySet()) {
                                finalResult.contents.put(entry.getKey(), entry.getValue());
                            }
                            finalResult.addUsage(res.inputTokens, res.outputTokens, res.totalTokens);
                        } catch (Exception e) {
                            // 忽略单个失败
                        }
                    }
                    return finalResult;
                });
    }

    /**
     * 异步调用 Ollama generate API，不阻塞当前线程
     */
    private CompletableFuture<AiResult> callApiAsync(AiProviderConfig config, String lang, String text) {
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
                            AiResult res = new AiResult();
                            String extracted = extractTextFromResponse(root);
                            res.contents.put(lang, extracted.isBlank() ? text : extracted);

                            // Ollama token usage
                            int promptTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").asInt() : 0;
                            int evalCount = root.has("eval_count") ? root.get("eval_count").asInt() : 0;
                            res.addUsage(promptTokens, evalCount, promptTokens + evalCount);

                            return res;
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
    public CompletionStage<AiResult> moderate(AiProviderConfig config, String prompt, String content) {
        String finalPrompt = prompt.replace("{{content}}", content != null ? content : "");
        if (!prompt.contains("{{content}}")) {
            finalPrompt = prompt + "\n\n内容如下：\n" + content;
        }

        try {
            URI uri = resolveUri(config, "/api/chat");
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", chooseModel(config, "llama3"));
            payload.put("stream", false);
            payload.put("messages", List.of(Map.of("role", "user", "content", finalPrompt)));
            payload.put("format", "json");

            String bodyJson = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("Ollama 审核失败: " + response.statusCode() + " " + response.body());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            String resultText = extractTextFromResponse(root);
                            JsonNode resultJson = objectMapper.readTree(resultText);

                            AiResult res = new AiResult();
                            res.isSafe = resultJson.has("isSafe") ? resultJson.get("isSafe").asBoolean() : true;
                            res.errorMessage = resultJson.has("reason") ? resultJson.get("reason").asText() : null;
                            res.score = resultJson.has("score") ? resultJson.get("score").asInt() : null;
                            res.rawResponse = response.body();

                            // Ollama usage info is usually in prompt_eval_count and eval_count
                            int promptTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").asInt() : 0;
                            int completionTokens = root.has("eval_count") ? root.get("eval_count").asInt() : 0;
                            res.addUsage(promptTokens, completionTokens, promptTokens + completionTokens);

                            return res;
                        } catch (Exception e) {
                            log.error("解析 Ollama 审核响应失败", e);
                            AiResult fallback = new AiResult();
                            fallback.isSafe = true;
                            return fallback;
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public io.smallrye.mutiny.Multi<String> testStream(AiProviderConfig config, com.biliwind.blog.controller.api.admin.dto.AiTestRequest request) {
        return io.smallrye.mutiny.Multi.createFrom().emitter(emitter -> {
            try {
                URI uri = resolveUri(config, "/api/chat");
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", chooseModel(config, "llama3"));
                payload.put("stream", request.stream());
                
                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }

                if (request.imageUrls() != null && !request.imageUrls().isEmpty()) {
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

                if (request.stream()) {
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
                } else {
                    httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                            .whenComplete((res, err) -> {
                                if (err != null) {
                                    emitter.fail(err);
                                    return;
                                }
                                if (res.statusCode() >= 400) {
                                    emitter.fail(new RuntimeException("API 调用失败, code=" + res.statusCode() + ", body=" + res.body()));
                                    return;
                                }
                                try {
                                    JsonNode root = objectMapper.readTree(res.body());
                                    String text = extractTextFromResponse(root);
                                    emitter.emit("{\"type\":\"content\",\"content\":" + objectMapper.writeValueAsString(text) + "}");
                                    emitter.complete();
                                } catch (Exception e) {
                                    emitter.fail(e);
                                }
                            });
                }
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }
}
