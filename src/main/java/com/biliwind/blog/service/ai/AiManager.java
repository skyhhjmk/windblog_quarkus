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
        
        log.info("[AI] 开始生成摘要，接管配置={}", best.name);
        return executeSummarize(best, content).exceptionallyCompose(error -> {
            AiProviderConfig fallback = selectionService.fallback("summarize", configs, best);
            return fallback == null ? CompletableFuture.failedFuture(error) : executeSummarize(fallback, content);
        });
    }

    public CompletionStage<AiResult> moderate(String content) {
        List<AiProviderConfig> allConfigs = configService.listAll();
        List<AiProviderConfig> configs = new ArrayList<>(allConfigs);
        
        if (configs.isEmpty()) {
            AiResult defaultRes = new AiResult();
            defaultRes.isSafe = true;
            return CompletableFuture.completedFuture(defaultRes);
        }

        AiProviderConfig best = selectionService.select("moderate", configs);
        if (best == null) {
            AiResult defaultRes = new AiResult();
            defaultRes.isSafe = true;
            return CompletableFuture.completedFuture(defaultRes);
        }

        // 获取系统设置中的提示词
        String prompt = "你是一个评论审核专家。请审核以下评论内容，判断其是否包含不当内容（色情、暴力、政治敏感、广告垃圾等）。回答 JSON: {\"isSafe\": true/false, \"reason\": \"理由\", \"score\": 评分0-100}。待审核内容: {{content}}";
        com.biliwind.blog.model.SystemSetting setting = com.biliwind.blog.model.SystemSetting.findByKey("ai_comment_audit");
        if (setting != null && setting.configValue != null && setting.configValue.has("prompt")) {
            prompt = setting.configValue.get("prompt").asText();
        }
        final String effectivePrompt = prompt;

        log.info("[AI] 开始审核内容，使用配置={}, provider={}", best.name, best.provider);
        return executeModerate(best, effectivePrompt, content).exceptionallyCompose(error -> {
            AiProviderConfig fallback = selectionService.fallback("moderate", configs, best);
            return fallback == null ? CompletableFuture.failedFuture(error) : executeModerate(fallback, effectivePrompt, content);
        });
    }

    public CompletionStage<AiResult> translate(String sourceLanguage, String targetLanguage,
            Map<String, String> fields) {
        List<AiProviderConfig> allConfigs = configService.listAll();
        List<AiProviderConfig> configs = new ArrayList<>(allConfigs);

        if (configs.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("当前没有任何已启用的 AI 配置"));
        }

        AiProviderConfig selected = selectionService.select("translate", configs);
        if (selected == null) {
            return CompletableFuture.failedFuture(new RuntimeException("没有允许翻译操作的 AI 配置"));
        }

        return executeTranslate(selected, sourceLanguage, targetLanguage, fields).exceptionallyCompose(error -> {
            AiProviderConfig fallback = selectionService.fallback("translate", configs, selected);
            return fallback == null ? CompletableFuture.failedFuture(error)
                    : executeTranslate(fallback, sourceLanguage, targetLanguage, fields);
        });
    }

    public CompletionStage<AiResult> executeTranslate(AiProviderConfig config, String sourceLanguage,
            String targetLanguage, Map<String, String> fields) {
        if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
            return pollingService.get().translate(config, sourceLanguage, targetLanguage, fields);
        }

        AiService service = findService(config);
        if (service == null) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
        }
        return service.translate(config, sourceLanguage, targetLanguage, fields);
    }

    public CompletionStage<AiResult> executeSummarize(AiProviderConfig config, Map<String, String> content) {
        if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
            return pollingService.get().summarize(config, content);
        } else {
            AiService svc = findService(config);
            if (svc == null)
                return CompletableFuture.failedFuture(new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
            return svc.summarize(config, content);
        }
    }

    public CompletionStage<AiResult> executeModerate(AiProviderConfig config, String prompt, String content) {
        if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
            return pollingService.get().moderate(config, prompt, content);
        } else {
            AiService svc = findService(config);
            if (svc == null) {
                log.warn("[AI] 未找到支持该配置的 AI 服务: name={}, provider={}", config.name, config.provider);
                AiResult defaultRes = new AiResult();
                defaultRes.isSafe = true;
                return CompletableFuture.completedFuture(defaultRes);
            }
            return svc.moderate(config, prompt, content);
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
