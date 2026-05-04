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
    public CompletionStage<AiResult> summarize(AiProviderConfig config, Map<String, String> contentByLanguage) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("ChatGLM 配置未就绪"));
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
                            LOG.error("获取 ChatGLM 结果失败", e);
                        }
                    }
                    return finalResult;
                });
    }

    private CompletableFuture<AiResult> callApiAsync(AiProviderConfig config, String lang, String text) {
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
        boolean forceEndpoint = false;
        if (config.config != null && !config.config.isBlank()) {
            try {
                JsonNode extra = objectMapper.readTree(config.config);
                if (extra.has("force_endpoint")) {
                    forceEndpoint = extra.get("force_endpoint").asBoolean();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        String endpoint;
        if (forceEndpoint && config.endpoint != null && !config.endpoint.isBlank()) {
            endpoint = resolveEndpoint(config.endpoint);
        } else {
            endpoint = DEFAULT_ENDPOINT;
        }

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
    public CompletionStage<AiResult> moderate(AiProviderConfig config, String prompt, String content) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new RuntimeException("ChatGLM 配置未就绪"));
        }

        String finalPrompt = prompt.replace("{{content}}", content != null ? content : "");
        if (!prompt.contains("{{content}}")) {
            finalPrompt = prompt + "\n\n内容如下：\n" + content;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", (config.model == null || config.model.isBlank()) ? DEFAULT_MODEL : config.model);
        payload.put("messages", List.of(Map.of("role", "user", "content", finalPrompt)));
        payload.put("response_format", Map.of("type", "json_object"));

        try {
            HttpRequest httpRequest = buildRequest(config, payload);
            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("ChatGLM 审核调用失败: " + response.statusCode() + " " + response.body());
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

                            if (root.has("usage")) {
                                JsonNode usage = root.get("usage");
                                int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                                int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                                int totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
                                res.addUsage(promptTokens, completionTokens, totalTokens);
                            }
                            return res;
                        } catch (Exception e) {
                            LOG.error("解析 ChatGLM 审核响应失败", e);
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
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", (config.model == null || config.model.isBlank()) ? DEFAULT_MODEL : config.model);
                payload.put("stream", request.stream());
                
                List<Map<String, Object>> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(Map.of("role", "system", "content", request.systemPrompt()));
                }
                messages.add(Map.of("role", "user", "content", request.prompt()));
                payload.put("messages", messages);

                HttpRequest httpRequest = buildRequest(config, payload);
                LOG.infof("开始 ChatGLM 连通性测试, stream: %b, endpoint: %s", request.stream(), httpRequest.uri());

                if (request.stream()) {
                    httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines())
                        .whenComplete((res, err) -> {
                            if (err != null) {
                                LOG.error("ChatGLM 测试流请求失败", err);
                                emitter.fail(err);
                                return;
                            }
                            LOG.infof("ChatGLM 响应状态码: %d", res.statusCode());
                            if (res.statusCode() >= 400) {
                                emitter.fail(new RuntimeException("API 调用失败, code=" + res.statusCode()));
                                return;
                            }
                            try (java.util.stream.Stream<String> lines = res.body()) {
                                lines.forEach(line -> {
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6).trim();
                                        if ("[DONE]".equals(data)) {
                                            LOG.info("ChatGLM 接收到 [DONE] 标记");
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
                                            LOG.warnf("解析 ChatGLM 行失败: %s, 错误: %s", data, ex.getMessage());
                                        }
                                    }
                                });
                            } catch (Exception e) {
                                LOG.error("流处理异常", e);
                            } finally {
                                LOG.info("ChatGLM 测试流结束");
                                emitter.complete();
                            }
                        });
                } else {
                    httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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
