package com.biliwind.blog.service;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostHelper;
import com.biliwind.blog.common.helper.SlugHelper;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiResult;
import com.biliwind.blog.service.ai.LocalAiTopicService;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.link.ArticleExternalLinkService;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * AI-provider article drafting for the topic module. The admin request only
 * creates a draft run; generation runs in the background so a slow provider
 * cannot trip the public edge timeout.
 */
@ApplicationScoped
public class AiArticleDraftService {

    private static final String SYSTEM_PROMPT = "You are a senior technical writer for WindBlog. "
            + "Write accurate, specific Markdown and return only the requested JSON.";

    @Inject
    AiManager aiManager;

    @Inject
    LocalAiTopicService topicService;

    @Inject
    com.biliwind.blog.service.ai.AiArticleDraftRunService runService;

    @Inject
    com.biliwind.blog.service.ai.AiArticleDraftRunner runner;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    ArticleExternalLinkService articleExternalLinkService;

    @Inject
    AuditService auditService;

    @Inject
    PostAiMetadataService postAiMetadataService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<PostSyncedEvent> postSyncedEvent;

    /** Creates an asynchronous draft run and returns it immediately. */
    public Map<String, Object> start(Long topicId, Long providerId, String language, String instructions,
                                     String repostPolicyCode, boolean regenerate) {
        if (topicId == null) {
            throw new BadRequestException("话题 ID 不能为空");
        }
        Long operatorId = adminRequestContext.getUserId();
        JsonNode topic = readTopic(topicId, operatorId);
        if (topic.path("title").asText("").isBlank()) {
            throw new BadRequestException("话题标题为空");
        }
        if (regenerate && !topic.path("postId").canConvertToLong()) {
            throw new BadRequestException("该话题还没有可重新生成的草稿");
        }
        Map<String, Object> active = runService.activeForTopic(topicId);
        if (active != null) return active;

        String effectiveLanguage = canonicalLanguage(language);
        String policy = repostPolicyCatalog.require(repostPolicyCode).code();
        Map<String, Object> run = runService.create(topicId, providerId, effectiveLanguage,
                truncate(instructions, 4000), policy, regenerate, operatorId);
        Long runId = run.get("id") instanceof Number number ? number.longValue() : null;
        if (runId == null) throw new IllegalStateException("draft run id missing");
        runner.start(runId, topicId, providerId, effectiveLanguage,
                truncate(instructions, 4000), policy, regenerate, operatorId);
        return run;
    }

    public Map<String, Object> job(Long runId) {
        return runService.view(runId);
    }

    public JsonNode readTopic(Long topicId, Long operatorId) {
        try {
            Map<String, Object> topic = dispatchTopic("topics.read", Map.of("id", topicId), operatorId);
            JsonNode node = objectMapper.valueToTree(topic);
            if (node == null || node.isNull() || node.isEmpty()) {
                throw new BadRequestException("话题不存在");
            }
            return node;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("读取话题失败: " + safeMessage(cause));
        }
    }

    /** Calls the configured AI provider. Runs outside any database transaction. */
    public DraftPayload generateContent(JsonNode topic, Long providerId, String language, String instructions) {
        String prompt = buildPrompt(topic, language, instructions);
        AiResult result;
        try {
            result = aiManager.complete(providerId, "topic", SYSTEM_PROMPT, prompt)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("AI 文章生成失败: " + safeMessage(cause));
        }
        String text = result == null || result.contents == null ? "" : result.contents.get("text");
        com.biliwind.blog.service.ai.AiArticleResponseParser.Article parsed =
                com.biliwind.blog.service.ai.AiArticleResponseParser.parse(objectMapper, text);
        return new DraftPayload(parsed.title(), parsed.summary(), parsed.contentMarkdown(), language, result);
    }

    /** Persists the generated draft and links it back to the topic. */
    @Transactional
    public DraftSaved persist(Long topicId, JsonNode topic, DraftPayload draft, String repostPolicyCode,
                              boolean regenerate, Long operatorId) {
        String title = draft.title();
        String summary = draft.summary();
        String content = draft.contentMarkdown();
        String effectiveLanguage = draft.language();
        User operator = currentOperator(operatorId);
        OffsetDateTime now = OffsetDateTime.now();
        String slug = uniqueSlug(title, topicId);
        Map<String, String> titleMap = Map.of(effectiveLanguage, title);
        Map<String, String> summaryMap = Map.of(effectiveLanguage, summary);
        Map<String, String> contentMap = PostHelper.injectBlockIds(
                Map.of(effectiveLanguage, content));

        Long existingPostId = topic.path("postId").canConvertToLong() ? topic.path("postId").asLong(0) : 0;
        Post post = null;
        if (existingPostId > 0) {
            post = Post.find("id = ?1", existingPostId)
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        }

        boolean isRegeneration = post != null;
        String effectivePolicy = (repostPolicyCode == null || repostPolicyCode.isBlank()) && post != null
                ? post.repostPolicyCode
                : repostPolicyCatalog.require(repostPolicyCode).code();
        if (isRegeneration) {
            if (post.status != PostStatus.DRAFT) {
                throw new BadRequestException("原文章已不再是草稿，拒绝覆盖其当前修订");
            }
            post.title = titleMap;
            post.summary = summaryMap;
            post.repostPolicyCode = effectivePolicy;
            post.updatedAt = now;
            post.persist();

            int nextRevision = (post.currentRevision != null
                    ? post.currentRevision.revisionNumber : 1) + 1;
            PostRevision revision = new PostRevision();
            revision.post = post;
            revision.title = titleMap;
            revision.contentMarkdown = contentMap;
            revision.editorType = 0;
            revision.revisionNumber = nextRevision;
            revision.createdBy = operator;
            revision.createdAt = now;
            revision.persist();
            post.currentRevision = revision;
        } else {
            post = new Post();
            post.slug = slug;
            post.title = titleMap;
            post.summary = summaryMap;
            post.status = PostStatus.DRAFT;
            post.visibility = 0;
            post.renderType = PostRenderType.MARKDOWN;
            post.aiSummaryStatus = 2;
            post.user = operator;
            post.repostPolicyCode = effectivePolicy;
            post.createdAt = now;
            post.updatedAt = now;
            post.persist();

            PostRevision revision = new PostRevision();
            revision.post = post;
            revision.title = titleMap;
            revision.contentMarkdown = contentMap;
            revision.editorType = 0;
            revision.revisionNumber = 1;
            revision.createdBy = operator;
            revision.createdAt = now;
            revision.persist();
            post.currentRevision = revision;
        }

        articleExternalLinkService.syncMarkdownLinks(post, contentMap);
        auditService.log("post", post.id,
                regenerate || isRegeneration ? "ai_article_draft_regenerate" : "ai_article_draft",
                null, null,
                Map.of("topicId", topicId, "language", effectiveLanguage,
                        "provider", draft.aiResult() == null || draft.aiResult().provider == null
                                ? "AI_MANAGER" : draft.aiResult().provider),
                operatorId);
        if (draft.aiResult() != null && post.currentRevision != null) {
            draft.aiResult().generationMode = regenerate || isRegeneration
                    ? "MANUAL_REGENERATION" : "MANUAL_ASSIGNMENT";
            postAiMetadataService.record(post.id, post.currentRevision.id, "article",
                    draft.aiResult(), "DRAFT");
        }
        postSyncedEvent.fire(new PostSyncedEvent(post.id));

        Map<String, Object> bindPayload = new LinkedHashMap<>();
        bindPayload.put("topicId", topicId);
        bindPayload.put("postId", post.id);
        bindPayload.put("language", effectiveLanguage);
        bindPayload.put("repostPolicyCode", post.repostPolicyCode);
        bindPayload.put("modelName", draft.aiResult() == null || draft.aiResult().modelId == null
                ? "" : draft.aiResult().modelId);
        bindPayload.put("provider", draft.aiResult() == null || draft.aiResult().provider == null
                ? "AI_MANAGER" : draft.aiResult().provider);
        dispatchTopic("topics.bindDraft", bindPayload, operatorId);

        return new DraftSaved(post.id, slug, title, summary);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchTopic(String command, Map<String, Object> payload, Long operatorId) {
        long actor = operatorId == null ? 0L : operatorId;
        Object result = topicService.dispatch(command, objectMapper.valueToTree(payload),
                "windblog-admin:" + actor, UUID.randomUUID().toString());
        if (result instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return Map.of();
    }

    private String buildPrompt(JsonNode topic, String language, String instructions) {
        String title = topic.path("title").asText("");
        String rationale = topic.path("rationale").asText("");
        JsonNode source = topic.path("source");
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下选题写一篇可直接进入编辑器的技术文章。\n")
                .append("选题：").append(title).append("\n")
                .append("推荐理由：").append(rationale).append("\n")
                .append("文章语言：").append(language).append("\n");
        JsonNode keywords = source.path("keywords");
        if (keywords.isArray() && !keywords.isEmpty()) {
            prompt.append("关键词：");
            keywords.forEach(item -> prompt.append(item.asText("")).append("、"));
            prompt.append("\n");
        }
        JsonNode sources = source.path("sources");
        if (sources.isArray() && !sources.isEmpty()) {
            prompt.append("可用来源（不得编造新 URL）：\n");
            int count = 0;
            for (JsonNode item : sources) {
                if (count++ >= 8) break;
                prompt.append("- ").append(item.path("title").asText("")).append(" ")
                        .append(item.path("url").asText("")).append("\n");
            }
        }
        if (instructions != null && !instructions.isBlank()) {
            prompt.append("额外要求：").append(truncate(instructions, 4000)).append("\n");
        }
        prompt.append("""
                硬性要求：
                1. 只输出一个 JSON 对象，键为 title、summary、contentMarkdown，所有值均为字符串；不要输出代码围栏或解释。
                2. contentMarkdown 从二级标题开始，不要重复页面标题为一级标题；结构清晰，包含必要的代码块、步骤或对比。
                3. 中文文章至少 1200 个有效中文字符；英文文章至少 900 个单词。避免“在当今快速发展的时代”“本文将深入探讨”等空话。
                4. 不要编造事实、数据、URL 或引用；没有来源支持时明确说明限定条件。
                5. 保留 Markdown 标准语法，代码块需标注语言。
                """);
        return prompt.toString();
    }

    private String uniqueSlug(String title, Long topicId) {
        String base = SlugHelper.slugify(title);
        if (base.isBlank()) {
            base = "ai-article-" + topicId;
        }
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        String slug = base;
        int suffix = 2;
        while (Post.count("slug = ?1", slug) > 0) {
            slug = base + "-" + suffix;
            suffix++;
        }
        return slug;
    }

    private User currentOperator(Long operatorId) {
        User user = operatorId == null ? null
                : User.find("id = ?1 and status = 1 and deletedAt is null", operatorId).firstResult();
        if (user == null) {
            throw new BadRequestException("当前管理员不存在或已禁用");
        }
        return user;
    }

    private String canonicalLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "zh-cn";
        }
        String normalized = LanguageHelper.normalizeToSupportedLang(language);
        if (normalized != null) {
            return normalized;
        }
        String value = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return value.matches("^[a-z]{2,8}(?:-[a-z0-9]{2,8})*$") ? value : "zh-cn";
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record DraftPayload(String title, String summary, String contentMarkdown,
                               String language, AiResult aiResult) {
    }

    public record DraftSaved(Long postId, String slug, String title, String summary) {
    }
}
