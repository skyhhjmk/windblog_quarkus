package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Owns one AI call end to end: provider selection and degradation, polling
 * group expansion, rate/concurrency limits, prompt construction, response
 * parsing, timeout (transport-level, in the adapters), usage accounting and
 * result provenance. Callers only describe the operation and its payload.
 *
 * <p>All configuration and database reads happen synchronously on the calling
 * thread, before an asynchronous stage is returned, so retry callbacks never
 * need a CDI request or transaction context.
 */
@ApplicationScoped
public class AiCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiCallExecutor.class);

    @Inject
    Instance<AiService> providers;

    @Inject
    AiProviderConfigService configService;

    @Inject
    AiPollingService pollingService;

    @Inject
    AiSelectionService selectionService;

    @Inject
    AiRateLimiter rateLimiter;

    @Inject
    AiConcurrencyLimiter concurrencyLimiter;

    @Inject
    AiUsageService usageService;

    @Inject
    ObjectMapper objectMapper;

    public CompletionStage<AiResult> call(AiCallRequest request) {
        try {
            List<AiProviderConfig> configs = new ArrayList<>(configService.listAll());
            if (configs.isEmpty()) {
                return CompletableFuture.failedFuture(new RuntimeException("当前没有任何已启用的 AI 配置"));
            }
            AiProviderConfig primary = selectPrimary(request, configs);
            AiProviderConfig fallback = selectFallback(request, configs, primary);
            AiCallRequest effective = prepare(request);
            List<AiProviderConfig> primaryNodes = nodesOf(primary);
            List<AiProviderConfig> fallbackNodes = fallback == null ? null : nodesOf(fallback);
            CompletionStage<AiResult> stage = executePlanned(primary, primaryNodes, effective);
            if (fallback != null) {
                stage = stage.exceptionallyCompose(
                        error -> executePlanned(fallback, fallbackNodes, effective));
            }
            return stage;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public Multi<String> stream(AiCallRequest request) {
        try {
            List<AiProviderConfig> configs = new ArrayList<>(configService.listAll());
            if (configs.isEmpty()) {
                return Multi.createFrom().failure(new RuntimeException("当前没有任何已启用的 AI 配置"));
            }
            AiProviderConfig primary = selectPrimary(request, configs);
            AiProviderConfig fallback = selectFallback(request, configs, primary);
            AiCallRequest effective = prepare(request);
            Multi<String> stream = executeStream(primary, effective);
            if (fallback != null) {
                Multi<String> fallbackStream = executeStream(fallback, effective);
                stream = stream.onFailure().recoverWithMulti(error -> fallbackStream);
            }
            return stream;
        } catch (RuntimeException exception) {
            return Multi.createFrom().failure(exception);
        }
    }

    public Multi<String> executeStream(AiProviderConfig config, AiCallRequest request) {
        if (config.type == AiConfigType.POLLING_GROUP) {
            List<AiProviderConfig> nodes = pollingService.candidates(config);
            return streamNodes(nodes, 0, request);
        }
        return streamProvider(config, request);
    }

    public CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        AiService service = findService(config);
        if (service == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return service.fetchModels(config);
    }

    AiService findService(AiProviderConfig config) {
        if (config == null) return null;
        for (AiService service : providers) {
            if (service.supports(config)) {
                return service;
            }
        }
        return null;
    }

    /** Resolves polling-group nodes on the calling thread; null for a direct provider. */
    private List<AiProviderConfig> nodesOf(AiProviderConfig config) {
        return config.type == AiConfigType.POLLING_GROUP
                ? pollingService.candidates(config) : null;
    }

    private CompletionStage<AiResult> executePlanned(AiProviderConfig config,
                                                     List<AiProviderConfig> nodes,
                                                     AiCallRequest request) {
        if (nodes == null) {
            return executeProvider(config, request);
        }
        if (nodes.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("轮询组中所有节点调用失败或没有可用节点"));
        }
        return tryNode(nodes, 0, request);
    }

    private CompletionStage<AiResult> tryNode(List<AiProviderConfig> nodes, int index,
                                              AiCallRequest request) {
        if (index >= nodes.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中所有节点调用失败"));
        }
        AiProviderConfig node = nodes.get(index);
        return executeProvider(node, request).exceptionallyCompose(error -> {
            log.warn("轮询节点 {} 调用失败: {}", node.name, safeMessage(error));
            return tryNode(nodes, index + 1, request);
        });
    }

    private CompletionStage<AiResult> executeProvider(AiProviderConfig config, AiCallRequest request) {
        AiService service = findService(config);
        if (service == null) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
        }
        AiRateLimiter.Result rate = rateLimiter.tryAcquire(config);
        if (!rate.allowed()) {
            return CompletableFuture.failedFuture(new AiRateLimitException(
                    config.name == null ? "unknown" : config.name, rate.retryAfterSeconds()));
        }
        if (!concurrencyLimiter.tryAcquire(config)) {
            return CompletableFuture.failedFuture(new AiConcurrencyLimitException(
                    config.name == null ? "unknown" : config.name,
                    config.concurrencyLimit != null ? config.concurrencyLimit : 0));
        }
        long start = System.currentTimeMillis();
        CompletionStage<AiResult> stage;
        try {
            stage = invoke(service, config, request);
        } catch (Throwable error) {
            release(config);
            record(config, request, null, start, error);
            return CompletableFuture.failedFuture(error);
        }
        return stage.thenApply(result -> decorate(result, config))
                .whenComplete((result, error) -> {
                    release(config);
                    record(config, request, result, start, error);
                });
    }

    private Multi<String> streamNodes(List<AiProviderConfig> nodes, int index, AiCallRequest request) {
        if (index >= nodes.size()) {
            return Multi.createFrom().failure(new RuntimeException("轮询组中所有节点调用失败"));
        }
        AiProviderConfig node = nodes.get(index);
        return streamProvider(node, request).onFailure().recoverWithMulti(error -> {
            log.warn("轮询节点 {} 流式调用失败: {}", node.name, safeMessage(error));
            return streamNodes(nodes, index + 1, request);
        });
    }

    private Multi<String> streamProvider(AiProviderConfig config, AiCallRequest request) {
        return Multi.createFrom().deferred(() -> {
            AiService service = findService(config);
            if (service == null) {
                return Multi.createFrom().failure(
                        new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
            }
            AiRateLimiter.Result rate = rateLimiter.tryAcquire(config);
            if (!rate.allowed()) {
                return Multi.createFrom().failure(new AiRateLimitException(
                        config.name == null ? "unknown" : config.name, rate.retryAfterSeconds()));
            }
            if (!concurrencyLimiter.tryAcquire(config)) {
                return Multi.createFrom().failure(new AiConcurrencyLimitException(
                        config.name == null ? "unknown" : config.name,
                        config.concurrencyLimit != null ? config.concurrencyLimit : 0));
            }
            long start = System.currentTimeMillis();
            AiProviderRequest providerRequest = new AiProviderRequest(feature(request),
                    request.systemPrompt(), request.prompt(), false, request.imageUrls(), request.stream());
            Multi<String> stream;
            try {
                stream = service.stream(config, providerRequest);
            } catch (Throwable error) {
                release(config);
                record(config, request, null, start, error);
                return Multi.createFrom().failure(error);
            }
            return stream.onTermination().invoke((error, cancelled) -> {
                release(config);
                record(config, request, null, start, error);
            });
        });
    }

    private CompletionStage<AiResult> invoke(AiService service, AiProviderConfig config,
                                             AiCallRequest request) {
        return switch (request.operation()) {
            case SUMMARIZE -> summarize(service, config, request);
            case TRANSLATE -> translate(service, config, request);
            case MODERATE -> moderate(service, config, request);
            case COMPLETE -> complete(service, config, request);
            default -> CompletableFuture.failedFuture(
                    new IllegalArgumentException("该 AI 操作只支持流式调用"));
        };
    }

    private CompletionStage<AiResult> summarize(AiService service, AiProviderConfig config,
                                                AiCallRequest request) {
        Map<String, String> entries = request.structuredValues();
        if (entries.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("摘要内容不能为空"));
        }
        List<String> languages = new ArrayList<>(entries.keySet());
        List<CompletableFuture<AiResult>> futures = new ArrayList<>(languages.size());
        for (String language : languages) {
            String prompt = summarizePrompt(language, entries.get(language));
            futures.add(service.complete(config, AiProviderRequest.text("summarize", null, prompt))
                    .toCompletableFuture());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(ignored -> {
            AiResult merged = new AiResult();
            for (int i = 0; i < languages.size(); i++) {
                AiResult part = futures.get(i).join();
                merged.contents.put(languages.get(i), textOf(part));
                merged.addUsage(part.inputTokens, part.outputTokens, part.totalTokens);
            }
            return merged;
        });
    }

    private CompletionStage<AiResult> translate(AiService service, AiProviderConfig config,
                                                AiCallRequest request) {
        String prompt;
        try {
            prompt = TranslationPrompt.build(request.promptTemplate(), request.sourceLanguage(),
                    request.targetLanguage(), request.structuredValues(), objectMapper);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
        Set<String> fields = request.structuredValues().keySet();
        return service.complete(config, AiProviderRequest.json("translate", prompt))
                .thenApply(result -> parseTranslation(result, fields));
    }

    private CompletionStage<AiResult> moderate(AiService service, AiProviderConfig config,
                                               AiCallRequest request) {
        return service.complete(config, AiProviderRequest.json("moderate", moderationPrompt(request)))
                .thenApply(this::parseModeration);
    }

    private CompletionStage<AiResult> complete(AiService service, AiProviderConfig config,
                                               AiCallRequest request) {
        return service.complete(config, AiProviderRequest.text(feature(request),
                        request.systemPrompt(), request.prompt()))
                .thenApply(result -> {
                    if (textOf(result).isBlank()) {
                        throw new IllegalStateException("AI 提供商未返回写作结果");
                    }
                    return result;
                });
    }

    private AiResult parseTranslation(AiResult providerResult, Set<String> fields) {
        String text = AiJson.clean(textOf(providerResult));
        JsonNode translated;
        try {
            translated = objectMapper.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException("解析 AI 翻译响应失败", exception);
        }
        AiResult result = new AiResult();
        for (String field : fields) {
            JsonNode value = translated.get(field);
            if (value == null || !value.isTextual()) {
                throw new IllegalStateException("AI 翻译结果缺少字段: " + field);
            }
            result.contents.put(field, value.asText());
        }
        result.addUsage(providerResult.inputTokens, providerResult.outputTokens,
                providerResult.totalTokens);
        result.rawResponse = providerResult.rawResponse;
        return result;
    }

    private AiResult parseModeration(AiResult providerResult) {
        String text = AiJson.clean(textOf(providerResult));
        try {
            JsonNode decision = objectMapper.readTree(text);
            AiResult result = AiModerationResultParser.parse(decision,
                    providerResult.rawResponse == null ? text : providerResult.rawResponse);
            result.addUsage(providerResult.inputTokens, providerResult.outputTokens,
                    providerResult.totalTokens);
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("解析 AI 审核响应失败", exception);
        }
    }

    private AiResult decorate(AiResult result, AiProviderConfig config) {
        if (result != null && config != null) {
            if (result.modelId == null || result.modelId.isBlank()) {
                result.modelId = (config.model != null && !config.model.isBlank())
                        ? config.model : config.name;
            }
            if (result.provider == null || result.provider.isBlank()) {
                result.provider = config.provider;
            }
        }
        return result;
    }

    private AiProviderConfig selectPrimary(AiCallRequest request, List<AiProviderConfig> configs) {
        if (request.providerId() != null) {
            AiProviderConfig selected = configs.stream()
                    .filter(config -> config.id != null && config.id.equals(request.providerId())
                            && config.enabled)
                    .findFirst().orElse(null);
            if (selected == null) {
                throw new RuntimeException("指定的 AI 配置不存在或已禁用");
            }
            return selected;
        }
        String operation = operationTag(request);
        AiProviderConfig selected = selectionService.select(operation, configs);
        switch (request.operation()) {
            case SUMMARIZE, TRANSLATE, MODERATE, TEST_STREAM -> {
                if (selected == null) {
                    throw new RuntimeException("没有允许" + operationLabel(request) + "操作的 AI 配置");
                }
                return selected;
            }
            default -> {
            }
        }
        if (selected == null && selectionService.configuredId(operation) != null) {
            // An explicit selection that is disabled or does not allow this
            // operation must not silently route to another provider.
            throw new RuntimeException("功能配置的 AI 提供商不存在、已禁用或不支持该操作: " + operation);
        }
        if (selected == null && !"assistant".equals(operation)) {
            selected = selectionService.select("assistant", configs);
        }
        if (selected == null) {
            selected = selectionService.select("writing", configs);
        }
        if (selected == null) {
            selected = configs.stream().filter(config -> config.enabled).findFirst().orElse(null);
        }
        if (selected == null) {
            throw new RuntimeException("没有可用的 AI 配置");
        }
        return selected;
    }

    private AiProviderConfig selectFallback(AiCallRequest request, List<AiProviderConfig> configs,
                                            AiProviderConfig primary) {
        String operation = operationTag(request);
        AiProviderConfig fallback = selectionService.fallback(operation, configs, primary);
        switch (request.operation()) {
            case COMPLETE, COMPLETE_STREAM -> {
                if (fallback == null && !"assistant".equals(operation)) {
                    fallback = selectionService.fallback("assistant", configs, primary);
                }
                if (fallback == null) {
                    fallback = selectionService.fallback("writing", configs, primary);
                }
            }
            default -> {
            }
        }
        return fallback;
    }

    private AiCallRequest prepare(AiCallRequest request) {
        return switch (request.operation()) {
            case TRANSLATE -> request.promptTemplate() == null || request.promptTemplate().isBlank()
                    ? request.withPromptTemplate(configuredTranslationPrompt()) : request;
            case MODERATE -> request.withPromptTemplate(configuredModerationPrompt());
            default -> request;
        };
    }

    private String configuredTranslationPrompt() {
        SystemSetting setting = SystemSetting.findByKey("ai_translation");
        if (setting != null && setting.configValue != null) {
            String prompt = setting.configValue.path("prompt").asText("");
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        return TranslationPrompt.DEFAULT_PROMPT;
    }

    private String configuredModerationPrompt() {
        SystemSetting setting = SystemSetting.findByKey("ai_comment_audit");
        if (setting != null && setting.configValue != null && setting.configValue.has("prompt")) {
            String prompt = setting.configValue.get("prompt").asText("");
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        return CommentModerationPrompt.DEFAULT_PROMPT;
    }

    private String moderationPrompt(AiCallRequest request) {
        String template = request.promptTemplate() == null
                ? CommentModerationPrompt.DEFAULT_PROMPT : request.promptTemplate();
        String content = request.prompt() == null ? "" : request.prompt();
        if (template.contains("{{content}}")) {
            return template.replace("{{content}}", content);
        }
        return template + "\n\n内容如下：\n" + content;
    }

    private String summarizePrompt(String language, String text) {
        String languageHint = (language == null || language.isBlank()) ? "中文" : language;
        return "请用" + languageHint + "简洁地总结以下内容，控制在 120 字以内：\n"
                + (text == null ? "" : text);
    }

    private String operationTag(AiCallRequest request) {
        if (request.operationTag() != null && !request.operationTag().isBlank()) {
            return request.operationTag().trim();
        }
        return switch (request.operation()) {
            case SUMMARIZE -> "summarize";
            case TRANSLATE -> "translate";
            case MODERATE -> "moderate";
            default -> "assistant";
        };
    }

    private String operationLabel(AiCallRequest request) {
        return switch (request.operation()) {
            case SUMMARIZE -> "摘要";
            case TRANSLATE -> "翻译";
            case MODERATE -> "评论审核";
            default -> operationTag(request);
        };
    }

    private String feature(AiCallRequest request) {
        return operationTag(request);
    }

    private void release(AiProviderConfig config) {
        if (concurrencyLimiter != null) {
            concurrencyLimiter.release(config);
        }
    }

    private void record(AiProviderConfig config, AiCallRequest request, AiResult result,
                        long start, Throwable error) {
        if (usageService == null) return;
        usageService.record(config, feature(request), result, System.currentTimeMillis() - start, error);
    }

    private static String textOf(AiResult result) {
        if (result == null || result.contents == null) return "";
        String text = result.contents.get("text");
        return text == null ? "" : text;
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "unknown" : error.getMessage();
    }
}
