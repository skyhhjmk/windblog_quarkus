package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * AI 服务管理器：收集所有可用提供商，按优先级依次降级重试
 */
@ApplicationScoped
public class AiManager {

    private static final Logger log = LoggerFactory.getLogger(AiManager.class);

    @Inject
    Instance<AiService> providers;

    @Inject
    AiProviderConfigService configService;

    @Inject
    jakarta.enterprise.inject.Instance<AiPollingService> pollingService;

    @Inject
    AiSelectionService selectionService;

    @Inject
    AiRateLimiter rateLimiter;

    @Inject
    AiConcurrencyLimiter concurrencyLimiter;

    private AiService findService(AiProviderConfig config) {
        if (config == null) return null;
        for (AiService service : providers) {
            if (service.supports(config)) {
                return service;
            }
        }
        return null;
    }

    public CompletionStage<AiResult> summarize(Map<String, String> content) {
        List<AiProviderConfig> allConfigs = configService.listAll();
        List<AiProviderConfig> configs = new ArrayList<>(allConfigs);
        
        if (configs.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("当前没有任何已启用的 AI 配置"));
        }

        AiProviderConfig best = selectionService.select("summarize", configs);
        if (best == null) return CompletableFuture.failedFuture(new RuntimeException("没有允许摘要操作的 AI 配置"));
        
        // 降级配置必须在调用线程上解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        AiProviderConfig fallback = selectionService.fallback("summarize", configs, best);

        log.info("[AI] 开始生成摘要，接管配置={}", best.name);
        return executeSummarize(best, content).exceptionallyCompose(error ->
                fallback == null ? CompletableFuture.failedFuture(error) : executeSummarize(fallback, content));
    }

    public CompletionStage<AiResult> moderate(String content) {
        List<AiProviderConfig> allConfigs = configService.listAll();
        List<AiProviderConfig> configs = new ArrayList<>(allConfigs);
        
        if (configs.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("没有可用的 AI 审核配置"));
        }

        AiProviderConfig best = selectionService.select("moderate", configs);
        if (best == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("没有允许评论审核操作的 AI 配置"));
        }

        // 获取系统设置中的提示词
        String prompt = CommentModerationPrompt.DEFAULT_PROMPT;
        com.biliwind.blog.model.SystemSetting setting = com.biliwind.blog.model.SystemSetting.findByKey("ai_comment_audit");
        if (setting != null && setting.configValue != null && setting.configValue.has("prompt")) {
            prompt = setting.configValue.get("prompt").asText();
        }
        final String effectivePrompt = prompt;
        // 降级配置必须在调用线程上解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        AiProviderConfig fallback = selectionService.fallback("moderate", configs, best);

        log.info("[AI] 开始审核内容，使用配置={}, provider={}", best.name, best.provider);
        return executeModerate(best, effectivePrompt, content).thenApply(this::validateModerationResult)
                .exceptionallyCompose(error -> fallback == null ? CompletableFuture.failedFuture(error)
                        : executeModerate(fallback, effectivePrompt, content).thenApply(this::validateModerationResult));
    }

    private AiResult validateModerationResult(AiResult result) {
        if (result == null || result.isSafe == null) {
            throw new IllegalStateException("AI 审核响应缺少有效的安全判定");
        }
        if (result.score != null && (result.score < 0 || result.score > 100)) {
            throw new IllegalStateException("AI 审核响应评分超出 0 到 100 的范围");
        }
        return result;
    }

    public CompletionStage<AiResult> translate(String sourceLanguage, String targetLanguage,
            Map<String, String> fields) {
        return translate(sourceLanguage, targetLanguage, fields, null);
    }

    public CompletionStage<AiResult> translate(String sourceLanguage, String targetLanguage,
            Map<String, String> fields, String promptTemplate) {
        List<AiProviderConfig> allConfigs = configService.listAll();
        List<AiProviderConfig> configs = new ArrayList<>(allConfigs);

        if (configs.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("当前没有任何已启用的 AI 配置"));
        }

        AiProviderConfig selected = selectionService.select("translate", configs);
        if (selected == null) {
            return CompletableFuture.failedFuture(new RuntimeException("没有允许翻译操作的 AI 配置"));
        }
        String effectivePrompt = promptTemplate == null || promptTemplate.isBlank()
                ? configuredTranslationPrompt() : promptTemplate;
        // 降级配置必须在调用线程上解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        AiProviderConfig fallback = selectionService.fallback("translate", configs, selected);

        return executeTranslate(selected, sourceLanguage, targetLanguage, fields, effectivePrompt)
                .exceptionallyCompose(error -> fallback == null ? CompletableFuture.failedFuture(error)
                        : executeTranslate(fallback, sourceLanguage, targetLanguage, fields, effectivePrompt));
    }

    public CompletionStage<AiResult> executeTranslate(AiProviderConfig config, String sourceLanguage,
            String targetLanguage, Map<String, String> fields, String promptTemplate) {
        CompletionStage<AiResult> limited = rateLimited(config);
        if (limited != null) return limited;
        CompletionStage<AiResult> concLimited = concurrencyLimited(config);
        if (concLimited != null) return concLimited;
        try {
            CompletionStage<AiResult> stage;
            if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
                stage = pollingService.get().translate(config, sourceLanguage, targetLanguage, fields, promptTemplate);
            } else {
                AiService service = findService(config);
                if (service == null) {
                    concurrencyLimiter.release(config);
                    return CompletableFuture.failedFuture(
                            new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
                }
                stage = service.translate(config, sourceLanguage, targetLanguage, fields, promptTemplate);
            }
            return stage.whenComplete((res, ex) -> concurrencyLimiter.release(config));
        } catch (Throwable t) {
            concurrencyLimiter.release(config);
            return CompletableFuture.failedFuture(t);
        }
    }

    /**
     * Free-form completion for admin writing tools. The provider can be chosen
     * explicitly (including a polling group) or resolved by the configured
     * assistant/writing operation.
     */
    public CompletionStage<AiResult> complete(Long providerId, String systemPrompt, String prompt) {
        return complete(providerId, "assistant", systemPrompt, prompt);
    }

    /**
     * Free-form completion for a designated operation (e.g. "assistant", "topic").
     * When no providerId is supplied, the configured operation provider/polling-group is selected,
     * falling back to assistant/writing.
     */
    public CompletionStage<AiResult> complete(Long providerId, String operation, String systemPrompt, String prompt) {
        List<AiProviderConfig> configs = new ArrayList<>(configService.listAll());
        if (configs.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("当前没有任何已启用的 AI 配置"));
        }
        AiProviderConfig selected = null;
        if (providerId != null) {
            selected = configs.stream()
                    .filter(config -> config.id != null && config.id.equals(providerId) && config.enabled)
                    .findFirst().orElse(null);
            if (selected == null) {
                return CompletableFuture.failedFuture(new RuntimeException("指定的 AI 配置不存在或已禁用"));
            }
        } else {
            String op = (operation == null || operation.isBlank()) ? "assistant" : operation.trim();
            selected = selectionService.select(op, configs);
            if (selected == null && selectionService.configuredId(op) != null) {
                // An explicit selection that is disabled or does not allow this
                // operation must not silently fall back to another provider
                // (which could be the Codex Creator interface).
                return CompletableFuture.failedFuture(new RuntimeException(
                        "功能配置的 AI 提供商不存在、已禁用或不支持该操作: " + op));
            }
            if (selected == null && !"assistant".equals(op)) {
                selected = selectionService.select("assistant", configs);
            }
            if (selected == null) {
                selected = selectionService.select("writing", configs);
            }
            if (selected == null) {
                selected = configs.stream().filter(config -> config.enabled).findFirst().orElse(null);
            }
        }
        if (selected == null) {
            return CompletableFuture.failedFuture(new RuntimeException("没有可用的 AI 配置"));
        }
        final AiProviderConfig primary = selected;
        String op = (operation == null || operation.isBlank()) ? "assistant" : operation.trim();
        // 降级配置必须在调用线程上解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        AiProviderConfig fallback = selectionService.fallback(op, configs, primary);
        if (fallback == null && !"assistant".equals(op)) {
            fallback = selectionService.fallback("assistant", configs, primary);
        }
        if (fallback == null) {
            fallback = selectionService.fallback("writing", configs, primary);
        }
        final AiProviderConfig fallbackConfig = fallback;
        log.info("[AI] 开始完成调用，使用配置={}, provider={}, operation={}", primary.name, primary.provider, op);
        return executeComplete(primary, systemPrompt, prompt).exceptionallyCompose(error ->
                fallbackConfig == null ? CompletableFuture.failedFuture(error)
                        : executeComplete(fallbackConfig, systemPrompt, prompt));
    }

    public CompletionStage<AiResult> executeComplete(AiProviderConfig config, String systemPrompt, String prompt) {
        CompletionStage<AiResult> limited = rateLimited(config);
        if (limited != null) return limited;
        CompletionStage<AiResult> concLimited = concurrencyLimited(config);
        if (concLimited != null) return concLimited;
        try {
            CompletionStage<AiResult> stage;
            if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
                stage = pollingService.get().complete(config, systemPrompt, prompt);
            } else {
                AiService service = findService(config);
                if (service == null) {
                    concurrencyLimiter.release(config);
                    return CompletableFuture.failedFuture(
                            new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
                }
                stage = service.complete(config, systemPrompt, prompt);
            }
            return stage.whenComplete((res, ex) -> concurrencyLimiter.release(config));
        } catch (Throwable t) {
            concurrencyLimiter.release(config);
            return CompletableFuture.failedFuture(t);
        }
    }

    private CompletionStage<AiResult> rateLimited(AiProviderConfig config) {
        AiRateLimiter.Result result = rateLimiter.tryAcquire(config);
        if (result.allowed()) {
            return null;
        }
        return CompletableFuture.failedFuture(new AiRateLimitException(
                config == null || config.name == null ? "unknown" : config.name,
                result.retryAfterSeconds()));
    }

    private CompletionStage<AiResult> concurrencyLimited(AiProviderConfig config) {
        if (!concurrencyLimiter.tryAcquire(config)) {
            return CompletableFuture.failedFuture(new AiConcurrencyLimitException(
                    config == null || config.name == null ? "unknown" : config.name,
                    config != null && config.concurrencyLimit != null ? config.concurrencyLimit : 0));
        }
        return null;
    }

    private String configuredTranslationPrompt() {
        com.biliwind.blog.model.SystemSetting setting =
                com.biliwind.blog.model.SystemSetting.findByKey("ai_translation");
        if (setting != null && setting.configValue != null) {
            String prompt = setting.configValue.path("prompt").asText("");
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        return TranslationPrompt.DEFAULT_PROMPT;
    }

    public CompletionStage<AiResult> executeSummarize(AiProviderConfig config, Map<String, String> content) {
        CompletionStage<AiResult> limited = rateLimited(config);
        if (limited != null) return limited;
        CompletionStage<AiResult> concLimited = concurrencyLimited(config);
        if (concLimited != null) return concLimited;
        try {
            CompletionStage<AiResult> stage;
            if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
                stage = pollingService.get().summarize(config, content);
            } else {
                AiService svc = findService(config);
                if (svc == null) {
                    concurrencyLimiter.release(config);
                    return CompletableFuture.failedFuture(new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
                }
                stage = svc.summarize(config, content);
            }
            return stage.whenComplete((res, ex) -> concurrencyLimiter.release(config));
        } catch (Throwable t) {
            concurrencyLimiter.release(config);
            return CompletableFuture.failedFuture(t);
        }
    }

    public CompletionStage<AiResult> executeModerate(AiProviderConfig config, String prompt, String content) {
        CompletionStage<AiResult> limited = rateLimited(config);
        if (limited != null) return limited;
        CompletionStage<AiResult> concLimited = concurrencyLimited(config);
        if (concLimited != null) return concLimited;
        try {
            CompletionStage<AiResult> stage;
            if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
                stage = pollingService.get().moderate(config, prompt, content);
            } else {
                AiService svc = findService(config);
                if (svc == null) {
                    concurrencyLimiter.release(config);
                    log.warn("[AI] 未找到支持该配置的 AI 服务: name={}, provider={}", config.name, config.provider);
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "不支持的 AI 提供商引擎: " + config.provider));
                }
                stage = svc.moderate(config, prompt, content);
            }
            return stage.whenComplete((res, ex) -> concurrencyLimiter.release(config));
        } catch (Throwable t) {
            concurrencyLimiter.release(config);
            return CompletableFuture.failedFuture(t);
        }
    }

    public io.smallrye.mutiny.Multi<String> testStream(AiProviderConfig config, com.biliwind.blog.controller.api.admin.dto.AiTestRequest req) {
        if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
            return pollingService.get().testStream(config, req);
        } else {
            AiService svc = findService(config);
            if (svc == null)
                return io.smallrye.mutiny.Multi.createFrom().failure(new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
            return svc.testStream(config, req);
        }
    }

    public CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        AiService svc = findService(config);
        if (svc == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return svc.fetchModels(config);
    }
}
