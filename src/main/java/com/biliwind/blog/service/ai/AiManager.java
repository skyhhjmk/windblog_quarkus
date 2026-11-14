package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Caller-facing AI facade. Every operation runs through {@link AiCallExecutor},
 * which owns selection, degradation, polling groups, limits, timeouts,
 * accounting and context. Callers supply only the operation and its payload.
 */
@ApplicationScoped
public class AiManager {

    private static final Logger log = LoggerFactory.getLogger(AiManager.class);

    @Inject
    AiCallExecutor executor;

    public CompletionStage<AiResult> call(AiCallRequest request) {
        return executor.call(request);
    }

    public Multi<String> stream(AiCallRequest request) {
        return executor.stream(request);
    }

    public CompletionStage<AiResult> summarize(Map<String, String> content) {
        return call(AiCallRequest.summarize(content));
    }

    public CompletionStage<AiResult> translate(String sourceLanguage, String targetLanguage,
            Map<String, String> fields) {
        return call(AiCallRequest.translate(sourceLanguage, targetLanguage, fields));
    }

    public CompletionStage<AiResult> translate(String sourceLanguage, String targetLanguage,
            Map<String, String> fields, String promptTemplate) {
        return call(AiCallRequest.translate(sourceLanguage, targetLanguage, fields, promptTemplate));
    }

    public CompletionStage<AiResult> moderate(String content) {
        return call(AiCallRequest.moderate(content));
    }

    public CompletionStage<AiResult> moderateProfile(String content) {
        return call(AiCallRequest.moderateProfile(content));
    }

    public CompletionStage<AiResult> moderateAvatar(String prompt, List<String> imageUrls) {
        return call(AiCallRequest.moderateAvatar(prompt, imageUrls));
    }

    public CompletionStage<AiResult> complete(Long providerId, String systemPrompt, String prompt) {
        return complete(providerId, "assistant", systemPrompt, prompt);
    }

    public CompletionStage<AiResult> complete(Long providerId, String operation,
            String systemPrompt, String prompt) {
        return call(AiCallRequest.complete(providerId, operation, systemPrompt, prompt));
    }

    public Multi<String> completeStream(Long providerId, String systemPrompt, String prompt) {
        return stream(AiCallRequest.completeStream(providerId, systemPrompt, prompt));
    }

    /** Connectivity/writing test against an already-resolved provider or group. */
    public Multi<String> testStream(AiProviderConfig config, AiCallRequest request) {
        return executor.executeStream(config, request);
    }

    public CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        return executor.fetchModels(config);
    }

    public static final String SLUG_SYSTEM_PROMPT = """
            You are a technical SEO specialist. Convert the given article title into a concise, URL-safe English slug (kebab-case).
            Rules:
            1. Translate or summarize non-English titles into concise, natural English keywords.
            2. Only output lowercase letters (a-z), digits (0-9), and hyphens (-).
            3. Do NOT output any explanations, markdown code fences, quotes, or other punctuation.
            4. Keep it under 80 characters.
            """;

    /**
     * 根据文章标题生成 URL 友好的 slug。
     * 纯 ASCII 标题直接格式化；非 ASCII 标题优先调用 AI 翻译为英文 slug，
     * 若未配置 AI、调用失败或超时，自动回退到基于 ICU4J 的拼音音译生成。
     */
    public CompletionStage<String> generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return CompletableFuture.completedFuture("");
        }
        if (isAsciiSlugCandidate(title)) {
            return CompletableFuture.completedFuture(
                    com.biliwind.blog.common.helper.SlugHelper.slugify(title));
        }
        String prompt = "Title: " + title.trim();
        return call(AiCallRequest.complete(null, "slug", SLUG_SYSTEM_PROMPT, prompt))
                .thenApply(res -> {
                    String text = res == null ? null : res.contents.get("text");
                    String candidate = com.biliwind.blog.common.helper.SlugHelper.slugify(text);
                    if (candidate == null || candidate.isBlank()) {
                        log.debug("[AI] AI slug 为空，回退到拼音生成");
                        return com.biliwind.blog.common.helper.SlugHelper.slugify(title);
                    }
                    return candidate;
                })
                .exceptionally(ex -> {
                    log.debug("[AI] AI slug 生成失败，回退到拼音生成: {}", ex.getMessage());
                    return com.biliwind.blog.common.helper.SlugHelper.slugify(title);
                });
    }

    private static boolean isAsciiSlugCandidate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) return false;
        }
        return true;
    }
}
