package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 按优先级过滤出所有可用的 AI 提供商
     */
    private List<AiService> getAvailableProviders() {
        List<AiService> available = new ArrayList<>();
        for (AiService service : providers) {
            if (service.isAvailable()) {
                available.add(service);
            }
        }
        available.sort(Comparator.comparingInt(AiService::getPriority));
        return available;
    }

    /**
     * 使用可用提供商生成摘要，失败自动降级到下一个
     */
    public CompletionStage<Map<String, String>> summarize(Map<String, String> content) {
        List<AiService> available = getAvailableProviders();

        if (available.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("当前没有可用的 AI 提供商"));
        }

        log.info("[AI] 开始生成摘要，可用提供商数量={}", available.size());
        return tryNextProvider(available, 0, content);
    }

    /**
     * 递归尝试每个提供商，失败自动降级
     */
    private CompletionStage<Map<String, String>> tryNextProvider(
            List<AiService> available, int index, Map<String, String> content) {

        if (index >= available.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("所有 AI 提供商均调用失败"));
        }

        AiService current = available.get(index);
        log.info("[AI] 尝试提供商 {}，priority={}", current.getClass().getSimpleName(), current.getPriority());

        return current.summarize(content).handle((result, ex) -> {
            if (ex != null) {
                log.warn("[AI] 提供商 {} 失败：{}，尝试降级", current.getClass().getSimpleName(), ex.getMessage());
                return tryNextProvider(available, index + 1, content);
            }
            log.info("[AI] 提供商 {} 成功", current.getClass().getSimpleName());
            return CompletableFuture.completedStage(result);
        }).thenCompose(stage -> stage);
    }

    /**
     * 使用可用提供商审核内容，失败自动降级到下一个
     */
    public CompletionStage<Boolean> moderate(String content) {
        List<AiService> available = getAvailableProviders();

        if (available.isEmpty()) {
            log.warn("[AI] 当前没有可用的 AI 提供商用于内容审核，默认通过");
            return CompletableFuture.completedFuture(true);
        }

        log.info("[AI] 开始审核内容，可用提供商数量={}", available.size());
        return tryNextModerateProvider(available, 0, content);
    }

    /**
     * 递归尝试每个提供商进行审核，失败自动降级
     */
    private CompletionStage<Boolean> tryNextModerateProvider(
            List<AiService> available, int index, String content) {

        if (index >= available.size()) {
            log.warn("[AI] 所有 AI 提供商审核均调用失败，默认通过");
            return CompletableFuture.completedFuture(true);
        }

        AiService current = available.get(index);
        log.info("[AI] 尝试提供商用于审核 {}，priority={}", current.getClass().getSimpleName(), current.getPriority());

        return current.moderate(content).handle((result, ex) -> {
            if (ex != null) {
                log.warn("[AI] 提供商 {} 审核失败：{}，尝试降级", current.getClass().getSimpleName(), ex.getMessage());
                return tryNextModerateProvider(available, index + 1, content);
            }
            log.info("[AI] 提供商 {} 审核成功", current.getClass().getSimpleName());
            return CompletableFuture.completedStage(result);
        }).thenCompose(stage -> stage);
    }
}

