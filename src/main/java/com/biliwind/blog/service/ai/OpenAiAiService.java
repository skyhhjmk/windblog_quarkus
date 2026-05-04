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
 * OpenAI 兼容 AI 服务
 * 支持 OpenAI、Azure、DeepSeek、阿里云通义千问兼容接口等
 */
@ApplicationScoped
public class OpenAiAiService implements AiService {

    private static final Logger LOG = Logger.getLogger(OpenAiAiService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "OPENAI".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<AiResult> summarize(AiProviderConfig config, Map<String, String> contentByLanguage) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("OpenAI 配置未就绪"));
        }

        List<CompletableFuture<AiResult>> futures = new ArrayList<>();
        for (Map.Entry<String, String> entry : contentByLanguage.entrySet()) {
            String lang = entry.getKey();
            String text = entry.getValue();
            futures.add(callApiAsync(config, lang, text));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    AiResult finalResult = new AiResult();
                    for (CompletableFuture<AiResult> f : futures) {
                        try {
                            AiResult res = f.join();
                            for (Map.Entry<String, String> entry : res.contents.entrySet()) {
                                finalResult.contents.put(entry.getKey(), entry.getValue());
                            }
                            finalResult.addUsage(res.inputTokens, res.outputTokens, res.totalTokens);
                        } catch (Exception e) {
                            LOG.error("获取 AI 结果失败", e);
                        }
                    }
                    return finalResult;
                });
    }

    private CompletableFuture<AiResult> callApiAsync(AiProviderConfig config, String lang, String text) {
        try {
            URI uri = resolveUri(config);
            Map<String, Object> payload = buildPayload(config, buildPrompt(lang, text), false);
            String bodyJson = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

            attachApiKeyHeader(builder, config);

            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("OpenAI 调用失败: " + response.statusCode() + " " + response.body());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            AiResult res = new AiResult();
                            res.contents.put(lang, extractTextFromResponse(root));

                            // 提取 Token 消耗
                            if (root.has("usage")) {
                                JsonNode usage = root.get("usage");
                                int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                                int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                                int totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
                                res.addUsage(promptTokens, completionTokens, totalTokens);
                            }

                            return res;
                        } catch (Exception e) {
                            throw new RuntimeException("解析 OpenAI 响应失败", e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private URI resolveUri(AiProviderConfig config) {
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

    private Map<String, Object> buildPayload(AiProviderConfig config, String prompt, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", chooseModel(config, "gpt-3.5-turbo"));
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        payload.put("stream", stream);
        payload.put("temperature", 0.3);
        return payload;
    }

    private String chooseModel(AiProviderConfig config, String defaultModel) {
        return (config.model == null || config.model.isBlank()) ? defaultModel : config.model;
    }

    private String buildPrompt(String lang, String text) {
        String languageHint = (lang == null || lang.isBlank()) ? "中文" : lang;
        return "请用" + languageHint + "简洁地总结以下内容，控制在 120 字以内：\n" + (text == null ? "" : text);
    }

    private String extractTextFromResponse(JsonNode root) {
        if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
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

    @Override
    public CompletionStage<AiResult> moderate(AiProviderConfig config, String prompt, String content) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("OpenAI 配置未就绪"));
        }

        String finalPrompt = prompt.replace("{{content}}", content != null ? content : "");
        if (!prompt.contains("{{content}}")) {
            finalPrompt = prompt + "\n\n内容如下：\n" + content;
        }

        try {
            URI uri = resolveUri(config);
            Map<String, Object> payload = buildPayload(config, finalPrompt, false);
            payload.put("response_format", Map.of("type", "json_object")); // 强制 JSON 输出
            String bodyJson = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

            attachApiKeyHeader(builder, config);

            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("OpenAI 审核调用失败: " + response.statusCode() + " " + response.body());
                        }
                        try {
                            JsonNode root = objectMapper.readTree(response.body());
                            String resultText = extractTextFromResponse(root);
                            JsonNode resultJson = objectMapper.readTree(resultText);

                            AiResult res = new AiResult();
                            res.isSafe = resultJson.has("isSafe") ? resultJson.get("isSafe").asBoolean() : true;
                            res.reason = resultJson.has("reason") ? resultJson.get("reason").asText() : null;
                            res.score = resultJson.has("score") ? resultJson.get("score").asInt() : null;
                            res.rawResponse = response.body();

                            // 提取 Token 消耗
                            if (root.has("usage")) {
                                JsonNode usage = root.get("usage");
                                int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                                int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                                int totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
                                res.addUsage(promptTokens, completionTokens, totalTokens);
                            }

                            return res;
                        } catch (Exception e) {
                            LOG.error("解析 OpenAI 审核响应失败: " + response.body(), e);
                            AiResult fallback = new AiResult();
                            fallback.isSafe = true;
                            fallback.errorMessage = "解析 AI 响应失败，默认通过";
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
                URI uri = resolveUri(config);
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", chooseModel(config, "gpt-3.5-turbo"));
                payload.put("stream", request.stream());
                
                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }

                if (request.imageUrls() != null && !request.imageUrls().isEmpty()) {
                    List<Map<String, Object>> contentList = new ArrayList<>();
                    contentList.add(Map.of("type", "text", "text", request.prompt()));
                    for (String imgUrl : request.imageUrls()) {
                        contentList.add(Map.of("type", "image_url", "image_url", Map.of("url", imgUrl)));
                    }
                    messages.add(Map.of("role", "user", "content", contentList));
                } else {
                    messages.add(Map.of("role", "user", "content", request.prompt()));
                }
                payload.put("messages", messages);

                String bodyJson = objectMapper.writeValueAsString(payload);
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
                attachApiKeyHeader(builder, config);

                if (request.stream()) {
                    httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines())
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
                                        if ("[DONE]".equals(data)) {
                                            return;
                                        }
                                        try {
                                            JsonNode root = objectMapper.readTree(data);
                                            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                                                JsonNode delta = root.get("choices").get(0).get("delta");
                                                if (delta != null) {
                                                    if (delta.has("reasoning_content")) {
                                                        String reasoning = delta.get("reasoning_content").asText("");
                                                        if (!reasoning.isEmpty()) {
                                                            emitter.emit("{\"type\":\"reasoning\",\"content\":" + objectMapper.writeValueAsString(reasoning) + "}");
                                                        }
                                                    }
                                                    if (delta.has("content")) {
                                                        String content = delta.get("content").asText("");
                                                        if (!content.isEmpty()) {
                                                            emitter.emit("{\"type\":\"content\",\"content\":" + objectMapper.writeValueAsString(content) + "}");
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Exception ex) {
                                            // Ignore partial json
                                        }
                                    }
                                });
                            }
                            emitter.complete();
                        });
                } else {
                    httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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
                    .header("Accept", "application/json")
                    .GET();

            attachApiKeyHeader(builder, config);

            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            LOG.warn("获取 OpenAI 模型列表失败: " + response.statusCode() + " " + response.body());
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
                        } catch (Exception e) {
                            LOG.error("解析 OpenAI 模型列表失败", e);
                            return List.of();
                        }
                    });
        } catch (Exception e) {
            LOG.error("获取 OpenAI 模型列表请求异常", e);
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
