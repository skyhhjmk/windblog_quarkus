package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private AiService findService(AiProviderConfig config) {
        if (config == null) return null;
        for (AiService service : providers) {
            if (service.supports(config)) {
                return service;
            }
        }
        return null;
    }

    public CompletionStage<Map<String, String>> summarize(Map<String, String> content) {
        List<AiProviderConfig> configs = configService.listAll().stream().filter(c -> c.enabled).toList();
        if (configs.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("当前没有任何已启用的 AI 配置"));
        }
        // Prefer polling group if exists
        AiProviderConfig best = configs.stream().filter(c -> c.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP).findFirst().orElse(configs.get(0));
        log.info("[AI] 开始生成摘要，接管配置={}", best.name);
        return executeSummarize(best, content);
    }

    public CompletionStage<Boolean> moderate(String content) {
        List<AiProviderConfig> configs = configService.listAll().stream().filter(c -> c.enabled).toList();
        if (configs.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        AiProviderConfig best = configs.stream().filter(c -> c.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP).findFirst().orElse(configs.get(0));
        log.info("[AI] 开始审核内容，接管配置={}", best.name);
        return executeModerate(best, content);
    }

    public CompletionStage<Map<String, String>> executeSummarize(AiProviderConfig config, Map<String, String> content) {
        if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
            return pollingService.get().summarize(config, content);
        } else {
            AiService svc = findService(config);
            if (svc == null)
                return CompletableFuture.failedFuture(new RuntimeException("不支持的 AI 提供商引擎: " + config.provider));
            return svc.summarize(config, content);
        }
    }

    public CompletionStage<Boolean> executeModerate(AiProviderConfig config, String content) {
        if (config.type == com.biliwind.blog.model.AiConfigType.POLLING_GROUP) {
            return pollingService.get().moderate(config, content);
        } else {
            AiService svc = findService(config);
            if (svc == null) return CompletableFuture.completedFuture(true);
            return svc.moderate(config, content);
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
}

