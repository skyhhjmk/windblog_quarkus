package com.biliwind.blog.service.ai;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.api.chat.ChatApi;
import ai.z.openapi.service.model.Audio;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatError;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import ai.z.openapi.service.model.Choice;
import ai.z.openapi.service.model.CompletionTokensDetails;
import ai.z.openapi.service.model.Delta;
import ai.z.openapi.service.model.ModelData;
import ai.z.openapi.service.model.PromptTokensDetails;
import ai.z.openapi.service.model.ResponseFormat;
import ai.z.openapi.service.model.ToolCalls;
import ai.z.openapi.service.model.ZAiError;
import ai.z.openapi.service.model.ZAiHttpException;
import ai.z.openapi.service.model.Usage;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AiTestRequest;
import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.security.ExternalHttpEndpointPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.runtime.annotations.RegisterForProxy;
import io.reactivex.rxjava3.disposables.Disposable;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** ChatGLM provider implemented with the official Z.AI Java SDK. */
@RegisterForReflection(targets = {
        ChatCompletionCreateParams.class,
        ChatCompletionResponse.class,
        ChatError.class,
        ChatMessage.class,
        ModelData.class,
        Choice.class,
        Delta.class,
        Usage.class,
        Audio.class,
        ToolCalls.class,
        PromptTokensDetails.class,
        CompletionTokensDetails.class,
        ResponseFormat.class,
        ZAiError.class,
        ZAiError.ZAiErrorDetails.class,
        ZAiError.ContentFilter.class
})
@RegisterForProxy(targets = ChatApi.class)
@ApplicationScoped
public class ChatGlmAiService implements AiService {

    private static final Logger LOG = Logger.getLogger(ChatGlmAiService.class);
    private static final String DEFAULT_MODEL = "glm-4-flash";
    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "CHATGLM".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<AiResult> summarize(AiProviderConfig config,
            Map<String, String> contentByLanguage) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChatGLM 配置未就绪"));
        }

        List<CompletableFuture<AiResult>> futures = new ArrayList<>();
        for (Map.Entry<String, String> entry : contentByLanguage.entrySet()) {
            futures.add(callApiAsync(config, entry.getKey(), entry.getValue()));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    AiResult result = new AiResult();
                    for (CompletableFuture<AiResult> future : futures) {
                        AiResult languageResult = future.join();
                        result.contents.putAll(languageResult.contents);
                        result.addUsage(languageResult.inputTokens, languageResult.outputTokens,
                                languageResult.totalTokens);
                    }
                    return result;
                });
    }

    @Override
    public CompletionStage<AiResult> translate(AiProviderConfig config, String sourceLanguage,
            String targetLanguage, Map<String, String> fields) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChatGLM 配置未就绪"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = buildTranslationPrompt(sourceLanguage, targetLanguage, fields);
                ModelData data = complete(config, List.of(userMessage(prompt)), false, true);
                JsonNode translated = objectMapper.readTree(extractText(data));

                AiResult result = new AiResult();
                for (String field : fields.keySet()) {
                    JsonNode value = translated.get(field);
                    if (value == null || !value.isTextual()) {
                        throw new IllegalStateException("AI 翻译结果缺少字段: " + field);
                    }
                    result.contents.put(field, value.asText());
                }
                addUsage(data, result);
                return result;
            } catch (Exception exception) {
                throw asCompletionException("ChatGLM 翻译调用失败", exception);
            }
        });
    }

    private CompletableFuture<AiResult> callApiAsync(AiProviderConfig config, String language,
            String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ModelData data = complete(config, List.of(userMessage(buildPrompt(language, text))), false, false);
                AiResult result = new AiResult();
                result.contents.put(language, extractText(data));
                addUsage(data, result);
                return result;
            } catch (Exception exception) {
                throw asCompletionException("ChatGLM 摘要调用失败", exception);
            }
        });
    }

    @Override
    public CompletionStage<AiResult> moderate(AiProviderConfig config, String prompt, String content) {
        if (!isConfigReady(config)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChatGLM 配置未就绪"));
        }

        String sourcePrompt = prompt == null ? "" : prompt;
        String finalPrompt = sourcePrompt.replace("{{content}}", content == null ? "" : content);
        if (!sourcePrompt.contains("{{content}}")) {
            finalPrompt = sourcePrompt + "\n\n内容如下：\n" + (content == null ? "" : content);
        }
        String moderationPrompt = finalPrompt;

        return CompletableFuture.supplyAsync(() -> {
            try {
                ModelData data = complete(config, List.of(userMessage(moderationPrompt)), false, true);
                String responseText = extractText(data);
                JsonNode decision = objectMapper.readTree(responseText);
                AiResult result = AiModerationResultParser.parse(decision,
                        objectMapper.writeValueAsString(data));
                addUsage(data, result);
                return result;
            } catch (Exception exception) {
                LOG.error("解析 ChatGLM 审核响应失败", exception);
                throw asCompletionException("ChatGLM 审核调用失败", exception);
            }
        });
    }

    @Override
    public Multi<String> testStream(AiProviderConfig config, AiTestRequest request) {
        return Multi.createFrom().emitter(emitter -> {
            AtomicBoolean terminated = new AtomicBoolean();
            AtomicInteger responseBytes = new AtomicInteger();
            AtomicReference<ZhipuAiClient> clientRef = new AtomicReference<>();
            AtomicReference<Disposable> streamRef = new AtomicReference<>();

            emitter.onTermination(() -> {
                terminated.set(true);
                Disposable disposable = streamRef.getAndSet(null);
                if (disposable != null) {
                    disposable.dispose();
                }
                closeClient(clientRef.getAndSet(null));
            });

            if (!isConfigReady(config)) {
                emitTestError(emitter, responseBytes, new IllegalStateException("ChatGLM 配置未就绪"));
                return;
            }

            List<ChatMessage> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(message(ChatMessageRole.SYSTEM, request.systemPrompt()));
            }
            messages.add(userMessage(request.prompt()));

            String baseUrl;
            try {
                baseUrl = resolveBaseUrl(config);
            } catch (Exception exception) {
                emitTestError(emitter, responseBytes, exception);
                return;
            }

            LOG.infof("开始 ChatGLM SDK 连通性测试, stream: %b, endpoint: %s", request.stream(), baseUrl);
            CompletableFuture.supplyAsync(() -> {
                ZhipuAiClient client = createClient(config, baseUrl);
                try {
                    ChatCompletionResponse response = client.chat().createChatCompletion(
                            buildRequest(config, messages, request.stream(), false));
                    return new TestCall(client, response);
                } catch (RuntimeException exception) {
                    closeClient(client);
                    throw exception;
                }
            }).whenComplete((call, error) -> {
                if (error != null) {
                    if (!terminated.get()) {
                        Throwable cause = unwrap(error);
                        LOG.error("ChatGLM SDK 连通性测试失败", cause);
                        emitTestError(emitter, responseBytes, cause);
                    }
                    return;
                }

                clientRef.set(call.client());
                if (terminated.get()) {
                    closeClient(clientRef.getAndSet(null));
                    return;
                }

                ChatCompletionResponse response = call.response();
                if (request.stream()) {
                    LOG.info("ChatGLM SDK 测试流已初始化");
                } else {
                    LOG.infof("ChatGLM SDK 测试响应状态码: %d", response.getCode());
                }
                if (!response.isSuccess()) {
                    closeClient(clientRef.getAndSet(null));
                    emitTestError(emitter, responseBytes, new IllegalStateException("API 调用失败: "
                            + SensitiveMessageSanitizer.sanitize(response.getMsg())));
                    return;
                }

                if (request.stream()) {
                    if (response.getFlowable() == null) {
                        closeClient(clientRef.getAndSet(null));
                        emitTestError(emitter, responseBytes,
                                new IllegalStateException("ChatGLM SDK 未返回流式响应"));
                        return;
                    }
                    Disposable disposable = response.getFlowable().subscribe(
                            data -> emitStreamData(data, responseBytes, emitter),
                            streamError -> {
                                LOG.error("ChatGLM SDK 测试流请求失败", streamError);
                                closeClient(clientRef.getAndSet(null));
                                if (!terminated.get()) {
                                    emitTestError(emitter, responseBytes, streamError);
                                }
                            },
                            () -> {
                                LOG.info("ChatGLM SDK 测试流结束");
                                closeClient(clientRef.getAndSet(null));
                                if (!terminated.get()) {
                                    emitter.complete();
                                }
                            });
                    streamRef.set(disposable);
                    if (terminated.get()) {
                        disposable.dispose();
                        closeClient(clientRef.getAndSet(null));
                    }
                    return;
                }

                try {
                    ModelData data = requireResponseData(response);
                    emitContent(extractText(data), responseBytes, emitter);
                    emitter.complete();
                } catch (Exception exception) {
                    emitTestError(emitter, responseBytes, exception);
                } finally {
                    closeClient(clientRef.getAndSet(null));
                }
            });
        });
    }

    private void emitTestError(MultiEmitter<? super String> emitter, AtomicInteger responseBytes,
            Throwable error) {
        Throwable cause = unwrap(error);
        String message;
        if (cause instanceof ZAiHttpException providerError) {
            String providerMessage = providerError.msg == null || providerError.msg.isBlank()
                    ? "未提供错误说明"
                    : SensitiveMessageSanitizer.sanitize(providerError.msg);
            String providerCode = providerError.code == null || providerError.code.isBlank()
                    ? ""
                    : " (" + SensitiveMessageSanitizer.sanitize(providerError.code) + ")";
            message = "ChatGLM 服务返回 HTTP " + providerError.statusCode + providerCode + ": "
                    + providerMessage;
        } else {
            String detail = cause.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = cause.getClass().getSimpleName();
            }
            message = "ChatGLM 测试失败: " + SensitiveMessageSanitizer.sanitize(detail);
        }

        try {
            emitter.emit(encodeChunk("error", message, responseBytes));
            emitter.complete();
        } catch (Exception emitError) {
            LOG.error("发送 ChatGLM 测试错误事件失败", emitError);
            emitter.fail(emitError);
        }
    }

    private ModelData complete(AiProviderConfig config, List<ChatMessage> messages,
            boolean stream, boolean jsonObject) {
        String baseUrl = resolveBaseUrl(config);
        ZhipuAiClient client = createClient(config, baseUrl);
        try {
            ChatCompletionResponse response = client.chat().createChatCompletion(
                    buildRequest(config, messages, stream, jsonObject));
            return requireResponseData(response);
        } finally {
            closeClient(client);
        }
    }

    private ChatCompletionCreateParams buildRequest(AiProviderConfig config,
            List<ChatMessage> messages, boolean stream, boolean jsonObject) {
        ChatCompletionCreateParams.ChatCompletionCreateParamsBuilder<?, ?> builder =
                ChatCompletionCreateParams.builder()
                        .model(resolveModel(config))
                        .messages(messages)
                        .stream(stream)
                        .temperature(0.3f);
        if (jsonObject) {
            builder.responseFormat(ResponseFormat.builder().type("json_object").build());
        }
        return builder.build();
    }

    private ZhipuAiClient createClient(AiProviderConfig config, String baseUrl) {
        ensureProxySupported(config);
        return ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(config.apiKey.trim())
                .baseUrl(baseUrl)
                .networkConfig(600, 60, 300, 60, TimeUnit.SECONDS)
                .build();
    }

    private String resolveBaseUrl(AiProviderConfig config) {
        if (!isForceEndpoint(config) || config.endpoint == null || config.endpoint.isBlank()) {
            return DEFAULT_BASE_URL;
        }

        String endpoint = config.endpoint.trim();
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception exception) {
            throw new IllegalArgumentException("ChatGLM endpoint 格式无效");
        }
        ExternalHttpEndpointPolicy.validateHttpUri(uri, "ChatGLM endpoint");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("ChatGLM endpoint 不能包含 query 或 fragment");
        }

        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (endpoint.toLowerCase(java.util.Locale.ROOT).endsWith(CHAT_COMPLETIONS_PATH)) {
            endpoint = endpoint.substring(0, endpoint.length() - CHAT_COMPLETIONS_PATH.length());
        }
        return endpoint + "/";
    }

    private boolean isForceEndpoint(AiProviderConfig config) {
        if (config.config == null || config.config.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(config.config).path("force_endpoint").asBoolean(false);
        } catch (Exception exception) {
            return false;
        }
    }

    private void ensureProxySupported(AiProviderConfig config) {
        if (config.config == null || config.config.isBlank()) {
            return;
        }
        try {
            JsonNode settings = objectMapper.readTree(config.config);
            if (settings.path("proxy_enabled").asBoolean(false)) {
                throw new IllegalStateException("当前 Z.AI SDK 不支持 WindBlog 的单提供商代理配置");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception ignored) {
            // Invalid optional settings were historically ignored by the provider.
        }
    }

    private ModelData requireResponseData(ChatCompletionResponse response) {
        if (response == null) {
            throw new IllegalStateException("ChatGLM SDK 未返回响应");
        }
        if (!response.isSuccess()) {
            throw new IllegalStateException("ChatGLM 调用失败: "
                    + SensitiveMessageSanitizer.sanitize(response.getMsg()));
        }
        if (response.getData() == null) {
            throw new IllegalStateException("ChatGLM 响应缺少结果数据");
        }
        ModelData data = response.getData();
        try {
            if (objectMapper.writeValueAsBytes(data).length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("ChatGLM 响应内容超过限制");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("ChatGLM 响应解析失败", exception);
        }
        return data;
    }

    private String extractText(ModelData data) {
        if (data == null || data.getChoices() == null || data.getChoices().isEmpty()) {
            return "";
        }
        ChatMessage answer = data.getChoices().get(0).getMessage();
        if (answer == null || answer.getContent() == null) {
            return "";
        }
        Object content = answer.getContent();
        return content instanceof String text ? text : content.toString();
    }

    private void addUsage(ModelData data, AiResult result) {
        Usage usage = data == null ? null : data.getUsage();
        if (usage != null) {
            result.addUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }
    }

    private String buildTranslationPrompt(String sourceLanguage, String targetLanguage,
            Map<String, String> fields) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("sourceLanguage", sourceLanguage);
        input.put("targetLanguage", targetLanguage);
        input.put("fields", fields);
        return "你是专业技术文章译者。请将以下 JSON 中 fields 的自然语言从 "
                + sourceLanguage + " 翻译为 " + targetLanguage
                + "，只返回包含全部原字段名的 JSON 对象。保留 Markdown、代码块、HTML、链接和图片 URL，"
                + "不要翻译代码、URL、属性值或标记，不要添加解释。\n"
                + objectMapper.writeValueAsString(input);
    }

    private String buildPrompt(String language, String text) {
        String languageHint = language == null || language.isBlank() ? "中文" : language;
        return "请用" + languageHint + "简洁地总结以下内容，控制在 120 字以内：\n"
                + (text == null ? "" : text);
    }

    private ChatMessage userMessage(String content) {
        return message(ChatMessageRole.USER, content);
    }

    private ChatMessage message(ChatMessageRole role, String content) {
        return ChatMessage.builder().role(role.value()).content(content).build();
    }

    private void emitStreamData(ModelData data, AtomicInteger responseBytes,
            MultiEmitter<? super String> emitter) {
        if (data == null || data.getChoices() == null) {
            return;
        }
        for (Choice choice : data.getChoices()) {
            Delta delta = choice == null ? null : choice.getDelta();
            if (delta == null) {
                continue;
            }
            emitChunk("reasoning", delta.getReasoningContent(), responseBytes, emitter);
            emitChunk("content", delta.getContent(), responseBytes, emitter);
        }
    }

    private void emitContent(String content, AtomicInteger responseBytes,
            MultiEmitter<? super String> emitter) throws Exception {
        emitter.emit(encodeChunk("content", content, responseBytes));
    }

    private void emitChunk(String type, String content, AtomicInteger responseBytes,
            MultiEmitter<? super String> emitter) {
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            emitter.emit(encodeChunk(type, content, responseBytes));
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private String encodeChunk(String type, String content, AtomicInteger responseBytes) throws Exception {
        String encoded = objectMapper.writeValueAsString(Map.of("type", type, "content", content));
        AiHttpClientHelper.consumeStreamingResponseBudget(responseBytes, encoded);
        return encoded;
    }

    private String resolveModel(AiProviderConfig config) {
        return config.model == null || config.model.isBlank() ? DEFAULT_MODEL : config.model;
    }

    private boolean isConfigReady(AiProviderConfig config) {
        return config != null && config.enabled && config.apiKey != null && !config.apiKey.isBlank();
    }

    private CompletionException asCompletionException(String message, Exception cause) {
        return new CompletionException(message + ": "
                + SensitiveMessageSanitizer.sanitize(cause.getMessage()), cause);
    }

    private Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void closeClient(ZhipuAiClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception exception) {
                LOG.debug("关闭 ChatGLM SDK 客户端失败", exception);
            }
        }
    }

    private record TestCall(ZhipuAiClient client, ChatCompletionResponse response) {
    }
}
