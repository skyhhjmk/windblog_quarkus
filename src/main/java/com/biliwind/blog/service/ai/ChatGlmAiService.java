package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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
 * ChatGLM AI 服务 - 使用 OpenAI 兼容协议 (V4)
 * 舍弃官方 SDK 以解决 Jackson 版本冲突问题
 */
@ApplicationScoped
public class ChatGlmAiService implements AiService {

    private static final Logger LOG = Logger.getLogger(ChatGlmAiService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_MODEL = "glm-4-flash";
    private static final String DEFAULT_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "CHATGLM".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<Map<String, String>> summarize(AiProviderConfig config, Map<String, String> contentByLanguage) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("ChatGLM 配置未就绪"));
        }

        List<CompletableFuture<Map.Entry<String, String>>> futures = new ArrayList<>();
        for (Map.Entry<String, String> entry : contentByLanguage.entrySet()) {
            String lang = entry.getKey();
            String text = entry.getValue();
            futures.add(callApiAsync(config, lang, text).thenApply(res -> Map.entry(lang, res)));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, String> result = new HashMap<>();
                    for (var f : futures) {
                        var entry = f.join();
                        result.put(entry.getKey(), entry.getValue());
                    }
                    return result;
                });
    }

    private CompletableFuture<String> callApiAsync(AiProviderConfig config, String lang, String text) {
        try {
            Map<String, Object> payload = buildPayload(config, buildPrompt(lang, text), false);
            HttpRequest request = buildRequest(config, payload);

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("ChatGLM 调用失败: " + response.statusCode() + " " + response.body());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            return extractTextFromResponse(root);
                        } catch (Exception e) {
                            throw new RuntimeException("解析 ChatGLM 响应失败", e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Map<String, Object> buildPayload(AiProviderConfig config, String prompt, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", (config.model == null || config.model.isBlank()) ? DEFAULT_MODEL : config.model);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        payload.put("stream", stream);
        payload.put("temperature", 0.3);
        return payload;
    }

    private HttpRequest buildRequest(AiProviderConfig config, Map<String, Object> payload) throws Exception {
        String endpoint = (config.endpoint == null || config.endpoint.isBlank()) ? DEFAULT_ENDPOINT : resolveEndpoint(config.endpoint);
        String json = objectMapper.writeValueAsString(payload);

        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey.trim())
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private String resolveEndpoint(String endpoint) {
        if (endpoint.endsWith("/chat/completions")) return endpoint;
        if (endpoint.endsWith("/")) return endpoint + "chat/completions";
        return endpoint + "/chat/completions";
    }

    private String buildPrompt(String lang, String text) {
        String languageHint = (lang == null || lang.isBlank()) ? "中文" : lang;
        return "请用" + languageHint + "简洁地总结以下内容，控制在 120 字以内：\n" + (text == null ? "" : text);
    }

    private String extractTextFromResponse(JsonNode root) {
        if (root.has("choices") && root.get("choices").size() > 0) {
            JsonNode message = root.get("choices").get(0).get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
        }
        return "";
    }

    private boolean isConfigReady(AiProviderConfig config) {
        return config != null && config.enabled && config.apiKey != null && !config.apiKey.isBlank();
    }

    @Override
    public CompletionStage<Boolean> moderate(AiProviderConfig config, String content) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public io.smallrye.mutiny.Multi<String> testStream(AiProviderConfig config, com.biliwind.blog.controller.api.admin.dto.AiTestRequest request) {
        return io.smallrye.mutiny.Multi.createFrom().emitter(emitter -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", (config.model == null || config.model.isBlank()) ? DEFAULT_MODEL : config.model);
                payload.put("stream", true);

                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }
                messages.add(Map.of("role", "user", "content", request.prompt()));
                payload.put("messages", messages);

                HttpRequest httpRequest = buildRequest(config, payload);
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines())
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
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6).trim();
                                        if ("[DONE]".equals(data)) return;
                                        try {
                                            JsonNode root = objectMapper.readTree(data);
                                            if (root.has("choices") && root.get("choices").size() > 0) {
                                                JsonNode delta = root.get("choices").get(0).get("delta");
                                                if (delta != null) {
                                                    if (delta.has("reasoning_content")) {
                                                        String reasoning = delta.get("reasoning_content").asText("");
                                                        if (!reasoning.isEmpty()) {
                                                            emitter.emit("{\"type\":\"reasoning\",\"content\":" + objectMapper.valueToTree(reasoning) + "}");
                                                        }
                                                    }
                                                    if (delta.has("content")) {
                                                        String content = delta.get("content").asText("");
                                                        if (!content.isEmpty()) {
                                                            emitter.emit("{\"type\":\"content\",\"content\":" + objectMapper.valueToTree(content) + "}");
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Exception ex) {
                                            // Handle partial json 
                                        }
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
