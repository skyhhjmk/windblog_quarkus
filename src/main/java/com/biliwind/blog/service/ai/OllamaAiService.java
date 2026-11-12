package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ollama AI 服务。使用 sendAsync 全程异步，不会阻塞调用线程。
 */
@ApplicationScoped
public class OllamaAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiService.class);

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "OLLAMA".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<AiResult> complete(AiProviderConfig config, AiProviderRequest request) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("Ollama 配置不可用"));
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", chooseModel(config, "llama3"));
            payload.put("stream", false);
            if (request.jsonResponse()) {
                payload.put("format", "json");
            }
            List<Map<String, Object>> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.systemPrompt()));
            }
            messages.add(Map.of("role", "user", "content",
                    request.prompt() == null ? "" : request.prompt()));
            payload.put("messages", messages);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(resolveUri(config, "/api/chat"))
                    .timeout(AiHttpClientHelper.getAiTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            AiHttpClientHelper.attachClientFeatureHeaders(builder, request.operation());

            return AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper),
                            builder.build(), AiHttpClientHelper.boundedStringBodyHandler())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("Ollama 调用失败，code=" + response.statusCode());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            AiResult result = new AiResult();
                            result.contents.put("text", extractTextFromResponse(root));
                            result.rawResponse = response.body();
                            int promptTokens = root.path("prompt_eval_count").asInt(0);
                            int completionTokens = root.path("eval_count").asInt(0);
                            result.addUsage(promptTokens, completionTokens,
                                    promptTokens + completionTokens);
                            return result;
                        } catch (Exception exception) {
                            throw new RuntimeException("解析 Ollama 响应失败", exception);
                        }
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public Multi<String> stream(AiProviderConfig config, AiProviderRequest request) {
        return Multi.createFrom().emitter(emitter -> {
            if (!isConfigReady(config)) {
                emitter.fail(new RuntimeException("Ollama 配置不可用"));
                return;
            }
            try {
                URI uri = resolveUri(config, "/api/chat");
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", chooseModel(config, "llama3"));
                payload.put("stream", request.stream());
                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }
                String prompt = request.prompt() == null ? "" : request.prompt();
                if (!request.imageUrls().isEmpty()) {
                    prompt = prompt + "\n[Images: " + String.join(", ", request.imageUrls()) + "]";
                }
                messages.add(Map.of("role", "user", "content", prompt));
                payload.put("messages", messages);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(AiHttpClientHelper.getAiTimeout())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
                AiHttpClientHelper.attachClientFeatureHeaders(builder, "test");

                if (request.stream()) {
                    streamResponse(config, builder.build(), emitter);
                } else {
                    completeResponse(config, builder.build(), emitter);
                }
            } catch (Exception exception) {
                emitter.fail(exception);
            }
        });
    }

    private void streamResponse(AiProviderConfig config, HttpRequest request,
                                MultiEmitter<? super String> emitter) {
        AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper), request,
                        HttpResponse.BodyHandlers.ofLines())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        emitter.fail(error);
                        return;
                    }
                    if (response.statusCode() >= 400) {
                        emitter.fail(new RuntimeException("API 调用失败, code=" + response.statusCode()));
                        return;
                    }
                    try (java.util.stream.Stream<String> lines = response.body()) {
                        AtomicInteger responseBytes = new AtomicInteger();
                        lines.forEach(line -> {
                            AiHttpClientHelper.consumeStreamingResponseBudget(responseBytes, line);
                            if (line.isBlank()) return;
                            try {
                                JsonNode root = objectMapper.readTree(line);
                                String content = root.path("message").path("content").asText("");
                                emitContent(emitter, content);
                            } catch (Exception ignored) {
                                // Ignore provider keep-alives and partial lines.
                            }
                        });
                    } catch (Exception streamException) {
                        emitter.fail(new IllegalStateException("AI 流响应处理失败"));
                        return;
                    }
                    emitter.complete();
                });
    }

    private void completeResponse(AiProviderConfig config, HttpRequest request,
                                  MultiEmitter<? super String> emitter) {
        AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper), request,
                        AiHttpClientHelper.boundedStringBodyHandler())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        emitter.fail(error);
                        return;
                    }
                    if (response.statusCode() >= 400) {
                        emitter.fail(new RuntimeException("API 调用失败，HTTP " + response.statusCode()));
                        return;
                    }
                    try {
                        JsonNode root = objectMapper.readTree(response.body());
                        emitContent(emitter, extractTextFromResponse(root));
                        emitter.complete();
                    } catch (Exception exception) {
                        emitter.fail(exception);
                    }
                });
    }

    private void emitContent(MultiEmitter<? super String> emitter, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            emitter.emit("{\"type\":\"content\",\"content\":"
                    + objectMapper.writeValueAsString(content) + "}");
        } catch (Exception ignored) {
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
        return config != null && config.enabled && config.endpoint != null
                && !config.endpoint.isBlank();
    }

    private String chooseModel(AiProviderConfig config, String fallback) {
        if (config.model == null || config.model.isBlank()) {
            return fallback;
        }
        return config.model;
    }

    private String extractTextFromResponse(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.has("response")) {
            return node.get("response").asText("");
        }
        if (node.has("message") && node.get("message").has("content")) {
            return node.get("message").get("content").asText("");
        }
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
    public CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        try {
            URI uri = resolveUri(config, "/api/tags");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(AiHttpClientHelper.getAiTimeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            return AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper),
                            request, AiHttpClientHelper.boundedStringBodyHandler())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            log.warn("获取 Ollama 模型列表失败: " + response.statusCode());
                            return List.of();
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            List<String> models = new ArrayList<>();
                            if (root.has("models") && root.get("models").isArray()) {
                                for (JsonNode node : root.get("models")) {
                                    if (node.has("name")) {
                                        models.add(node.get("name").asText());
                                    }
                                }
                            }
                            models.sort(String::compareToIgnoreCase);
                            return models;
                        } catch (Exception exception) {
                            log.error("解析 Ollama 模型列表失败", exception);
                            return List.of();
                        }
                    });
        } catch (Exception exception) {
            log.error("获取 Ollama 模型列表请求异常", exception);
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
