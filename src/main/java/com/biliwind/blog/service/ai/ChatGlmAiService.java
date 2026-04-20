package com.biliwind.blog.service.ai;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import com.biliwind.blog.model.AiProvider;
import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatGLM AI 摘要服务
 * 使用智谱AI官方 zai-sdk，不再手写 HTTP 请求
 */
@ApplicationScoped
public class ChatGlmAiService implements AiService {

    private static final Logger LOG = Logger.getLogger(ChatGlmAiService.class);

    /**
     * 官方 SDK 的同步调用会阻塞线程，因此用独立线程池执行，不占用 RabbitMQ 消费者线程
     */
    private static final ExecutorService AI_THREAD_POOL = Executors.newCachedThreadPool();

    /**
     * ChatGLM 文本摘要场景默认模型，免费额度可用
     */
    private static final String DEFAULT_MODEL = "glm-4-flash-250414";

    @Inject
    AiProviderConfigService configService;

    @Override
    public CompletionStage<Map<String, String>> summarize(Map<String, String> contentByLanguage) {
        AiProviderConfig config = loadEnabledConfig();
        if (config == null) {
            return CompletableFuture.failedFuture(new RuntimeException("ChatGLM 配置不可用"));
        }

        ZhipuAiClient client = buildClient(config);

        List<CompletableFuture<Map.Entry<String, String>>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : contentByLanguage.entrySet()) {
            String language = entry.getKey();
            String text = entry.getValue();

            CompletableFuture<Map.Entry<String, String>> future = callApiInBackground(client, config, language, text);
            futures.add(future);
        }

        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        return allDone.thenApply(ignored -> collectResults(futures));
    }

    /**
     * 用背景线程调用 SDK 同步接口，返回 CompletableFuture 让调用方异步等待
     */
    private CompletableFuture<Map.Entry<String, String>> callApiInBackground(
            ZhipuAiClient client,
            AiProviderConfig config,
            String language,
            String text) {

        return CompletableFuture.supplyAsync(() -> {
            String summary = callSdkSync(client, config, language, text);
            return Map.entry(language, summary);
        }, AI_THREAD_POOL);
    }

    /**
     * 同步调用 SDK，这个方法本身会阻塞，务必在背景线程中调用
     */
    private String callSdkSync(ZhipuAiClient client, AiProviderConfig config, String language, String text) {
        String modelName = chooseModel(config);
        String prompt = buildPrompt(language, text);

        ChatMessage userMessage = ChatMessage.builder()
                .role(ChatMessageRole.USER.value())
                .content(prompt)
                .build();

        List<ChatMessage> messages = Arrays.asList(userMessage);

        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(modelName)
                .messages(messages)
                .temperature(0.2f)
                .maxTokens(256)
                .build();

        ChatCompletionResponse response = client.chat().createChatCompletion(request);

        if (!response.isSuccess()) {
            String errorMessage = "ChatGLM 调用失败，code=" + response.getCode() + "，msg=" + response.getMsg();
            LOG.warn(errorMessage);
            throw new RuntimeException(errorMessage);
        }

        Object rawContent = response.getData().getChoices().get(0).getMessage().getContent();
        if (rawContent == null) {
            return text;
        }

        String summary = rawContent.toString().trim();
        if (summary.isBlank()) {
            return text;
        }

        return summary;
    }

    /**
     * 将所有 Future 的结果汇总到 Map，已在 allOf 之后调用，所以 join() 不会再阻塞
     */
    private Map<String, String> collectResults(List<CompletableFuture<Map.Entry<String, String>>> futures) {
        Map<String, String> summaries = new HashMap<>();
        for (CompletableFuture<Map.Entry<String, String>> future : futures) {
            Map.Entry<String, String> entry = future.join();
            summaries.put(entry.getKey(), entry.getValue());
        }
        return summaries;
    }

    /**
     * 构建 SDK 客户端
     * 没配置 endpoint 就走官方线路（ZhipuAiClient.ofZHIPU）
     * 配置了自定义 endpoint 则用通用 ZaiClient.baseUrl，适用于私有化部署
     */
    private ZhipuAiClient buildClient(AiProviderConfig config) {
        boolean hasCustomEndpoint = config.endpoint != null && !config.endpoint.isBlank();

        if (hasCustomEndpoint) {
            return ZhipuAiClient.builder()
                    .baseUrl(config.endpoint.trim())
                    .apiKey(config.apiKey.trim())
                    .build();
        }

        return ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(config.apiKey.trim())
                .build();
    }

    private AiProviderConfig loadEnabledConfig() {
        return configService.getByProvider(AiProvider.CHATGLM)
                .filter(this::isConfigReady)
                .orElse(null);
    }

    private boolean isConfigReady(AiProviderConfig config) {
        if (!config.enabled) {
            return false;
        }
        if (config.apiKey == null || config.apiKey.isBlank()) {
            return false;
        }
        return true;
    }

    private String chooseModel(AiProviderConfig config) {
        if (config.model == null || config.model.isBlank()) {
            return DEFAULT_MODEL;
        }
        return config.model;
    }

    private String buildPrompt(String language, String text) {
        String languageHint;
        if (language == null || language.isBlank()) {
            languageHint = "中文";
        } else {
            languageHint = language;
        }

        String safeText;
        if (text == null) {
            safeText = "";
        } else {
            safeText = text;
        }

        return "请用" + languageHint + "简洁地总结以下内容，控制在 120 字以内：\n" + safeText;
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
