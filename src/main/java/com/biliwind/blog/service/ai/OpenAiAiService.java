package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容 AI 服务
 * 支持 OpenAI、Azure、DeepSeek、阿里云通义千问兼容接口等
 */
@ApplicationScoped
public class OpenAiAiService implements AiService {

    private static final Logger LOG = Logger.getLogger(OpenAiAiService.class);

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "OPENAI".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<AiResult> complete(AiProviderConfig config, AiProviderRequest request) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("OpenAI 配置未就绪"));
        }
        try {
            Map<String, Object> payload = basePayload(config, false);
            List<Map<String, Object>> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.systemPrompt()));
            }
            messages.add(Map.of("role", "user", "content",
                    request.prompt() == null ? "" : request.prompt()));
            payload.put("messages", messages);
            if (request.jsonResponse()) {
                payload.put("response_format", Map.of("type", "json_object"));
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(resolveUri(config))
                    .timeout(AiHttpClientHelper.getAiTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            AiHttpClientHelper.attachClientFeatureHeaders(builder, request.operation());
            attachApiKeyHeader(builder, config);

            return AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper),
                            builder.build(), AiHttpClientHelper.boundedStringBodyHandler())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("OpenAI 调用失败，HTTP " + response.statusCode());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            AiResult result = new AiResult();
                            result.contents.put("text", extractTextFromResponse(root));
                            result.rawResponse = response.body();
                            applyUsage(root, result);
                            return result;
                        } catch (Exception exception) {
                            throw new RuntimeException("解析 OpenAI 响应失败", exception);
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
                emitJson(emitter, "error", "OpenAI 配置未就绪");
                emitter.complete();
                return;
            }
            try {
                Map<String, Object> payload = basePayload(config, request.stream());
                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }
                if (!request.imageUrls().isEmpty()) {
                    List<Map<String, Object>> contentList = new ArrayList<>();
                    contentList.add(Map.of("type", "text", "text",
                            request.prompt() == null ? "" : request.prompt()));
                    for (String imageUrl : request.imageUrls()) {
                        contentList.add(Map.of("type", "image_url",
                                "image_url", Map.of("url", imageUrl)));
                    }
                    messages.add(Map.of("role", "user", "content", contentList));
                } else {
                    messages.add(Map.of("role", "user", "content",
                            request.prompt() == null ? "" : request.prompt()));
                }
                payload.put("messages", messages);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(resolveUri(config))
                        .timeout(AiHttpClientHelper.getAiTimeout())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
                AiHttpClientHelper.attachClientFeatureHeaders(builder, "test");
                attachApiKeyHeader(builder, config);

                if (request.stream()) {
                    streamResponse(config, builder.build(), emitter);
                } else {
                    completeResponse(config, builder.build(), emitter);
                }
            } catch (Exception exception) {
                emitJson(emitter, "error", "[请求发送异常]: " + safeError(exception));
                emitter.complete();
            }
        });
    }

    private void streamResponse(AiProviderConfig config, HttpRequest request,
                                MultiEmitter<? super String> emitter) {
        AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper), request,
                        HttpResponse.BodyHandlers.ofLines())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        emitJson(emitter, "error", "[接口调用超时或异常]: " + safeError(error));
                        emitter.complete();
                        return;
                    }
                    if (response.statusCode() >= 400) {
                        String bodyText = "";
                        try (java.util.stream.Stream<String> lines = response.body()) {
                            AtomicInteger responseBytes = new AtomicInteger();
                            StringBuilder builder = new StringBuilder();
                            lines.forEach(line -> {
                                AiHttpClientHelper.consumeStreamingResponseBudget(responseBytes, line);
                                builder.append(line).append("\n");
                            });
                            bodyText = builder.toString();
                        } catch (Exception ignored) {
                        }
                        emitJson(emitter, "error", "[接口返回 HTTP " + response.statusCode() + "]: "
                                + SensitiveMessageSanitizer.sanitize(bodyText));
                        emitter.complete();
                        return;
                    }
                    try (java.util.stream.Stream<String> lines = response.body()) {
                        AtomicInteger responseBytes = new AtomicInteger();
                        lines.forEach(line -> {
                            AiHttpClientHelper.consumeStreamingResponseBudget(responseBytes, line);
                            if (!line.startsWith("data: ")) {
                                return;
                            }
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) {
                                return;
                            }
                            try {
                                JsonNode root = objectMapper.readTree(data);
                                JsonNode delta = root.path("choices").path(0).path("delta");
                                if (!delta.isMissingNode()) {
                                    emitJson(emitter, "reasoning", delta.path("reasoning_content").asText(""));
                                    emitJson(emitter, "content", delta.path("content").asText(""));
                                }
                            } catch (Exception ignored) {
                                // Ignore partial json
                            }
                        });
                    } catch (Exception streamException) {
                        emitJson(emitter, "error", "[接口流响应异常]: " + safeError(streamException));
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
                        emitJson(emitter, "error", "[接口调用超时或异常]: " + safeError(error));
                        emitter.complete();
                        return;
                    }
                    if (response.statusCode() >= 400) {
                        emitJson(emitter, "error", "[接口返回 HTTP " + response.statusCode() + "]");
                        emitter.complete();
                        return;
                    }
                    try {
                        JsonNode root = objectMapper.readTree(response.body());
                        emitJson(emitter, "content", extractTextFromResponse(root));
                        emitter.complete();
                    } catch (Exception exception) {
                        emitJson(emitter, "error", "[解析响应异常]: " + safeError(exception));
                        emitter.complete();
                    }
                });
    }

    private Map<String, Object> basePayload(AiProviderConfig config, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", chooseModel(config, "gpt-3.5-turbo"));
        payload.put("stream", stream);
        payload.put("temperature", 0.3);
        return payload;
    }

    private void applyUsage(JsonNode root, AiResult result) {
        if (root == null || !root.has("usage")) {
            return;
        }
        JsonNode usage = root.get("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);
        result.addUsage(promptTokens, completionTokens, totalTokens);
    }

    protected URI resolveUri(AiProviderConfig config) {
        String endpoint = config.endpoint;
        if (endpoint == null || endpoint.isBlank()) {
            return URI.create("https://api.openai.com/v1/chat/completions");
        }
        if (endpoint.endsWith("/chat/completions")) return URI.create(endpoint);
        if (endpoint.endsWith("/")) return URI.create(endpoint + "chat/completions");
        return URI.create(endpoint + "/chat/completions");
    }

    private void attachApiKeyHeader(HttpRequest.Builder builder, AiProviderConfig config) {
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey.trim());
        }
    }

    protected String chooseModel(AiProviderConfig config, String defaultModel) {
        return (config.model == null || config.model.isBlank()) ? defaultModel : config.model;
    }

    private String extractTextFromResponse(JsonNode root) {
        if (root != null && root.has("choices") && root.get("choices").isArray()
                && !root.get("choices").isEmpty()) {
            JsonNode message = root.get("choices").get(0).get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText("");
            }
        }
        return "";
    }

    private boolean isConfigReady(AiProviderConfig config) {
        return config != null && config.enabled && config.apiKey != null && !config.apiKey.isBlank();
    }

    private void emitJson(MultiEmitter<? super String> emitter, String type, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("type", type);
            payload.put("content", content);
            emitter.emit(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
        }
    }

    private String safeError(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        return SensitiveMessageSanitizer.sanitize(throwable.getMessage());
    }

    @Override
    public CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        try {
            String endpoint = config.endpoint;
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = "https://api.openai.com/v1";
            }
            if (endpoint.endsWith("/chat/completions")) {
                endpoint = endpoint.substring(0, endpoint.length() - "/chat/completions".length());
            } else if (endpoint.endsWith("/chat/completions/")) {
                endpoint = endpoint.substring(0, endpoint.length() - "/chat/completions/".length());
            }
            if (!endpoint.endsWith("/")) {
                endpoint = endpoint + "/";
            }
            endpoint = endpoint + "models";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET();
            attachApiKeyHeader(builder, config);

            return AiHttpClientHelper.sendAsync(AiHttpClientHelper.getClient(config, objectMapper),
                            builder.build(), AiHttpClientHelper.boundedStringBodyHandler())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            LOG.warn("获取 OpenAI 模型列表失败，HTTP " + response.statusCode());
                            return List.of();
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            List<String> models = new ArrayList<>();
                            if (root.has("data") && root.get("data").isArray()) {
                                for (JsonNode node : root.get("data")) {
                                    if (node.has("id")) {
                                        models.add(node.get("id").asText());
                                    }
                                }
                            }
                            models.sort(String::compareToIgnoreCase);
                            return models;
                        } catch (Exception exception) {
                            LOG.error("解析 OpenAI 模型列表失败", exception);
                            return List.of();
                        }
                    });
        } catch (Exception exception) {
            LOG.error("获取 OpenAI 模型列表请求异常", exception);
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
