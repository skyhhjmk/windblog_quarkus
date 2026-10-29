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
import com.biliwind.blog.service.ai.CodexCreatorHttpClient;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.link.ArticleExternalLinkService;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * API-provider article drafting for the topics module. The Codex Creator path
 * remains the default; this service lets an administrator switch a single
 * topic to a configured AI provider or polling group and receive a normal
 * WindBlog draft. The generated draft is linked back to the Codex Creator
 * topic pool so the assigned-tasks view stays consistent.
 */
@ApplicationScoped
public class AiArticleDraftService {

    private static final String SYSTEM_PROMPT = "You are a senior technical writer for WindBlog. "
            + "Write accurate, specific Markdown and return only the requested JSON.";

    @Inject
    AiManager aiManager;

    @Inject
    CodexCreatorHttpClient codexClient;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    ArticleExternalLinkService articleExternalLinkService;

    @Inject
    AuditService auditService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<PostSyncedEvent> postSyncedEvent;

    @Transactional
    public DraftResult generate(Long topicId, Long providerId, String language,
                                String instructions, String repostPolicyCode) {
        if (topicId == null) {
            throw new BadRequestException("话题 ID 不能为空");
        }
        JsonNode topic = readTopic(topicId);
        String topicTitle = topic.path("title").asText("").trim();
        if (topicTitle.isBlank()) {
            throw new BadRequestException("话题标题为空");
        }
        String effectiveLanguage = canonicalLanguage(language);
        String prompt = buildPrompt(topic, effectiveLanguage, instructions);
        AiResult result;
        try {
            result = aiManager.complete(providerId, SYSTEM_PROMPT, prompt)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("AI 文章生成失败: " + safeMessage(cause));
        }
        String text = result == null || result.contents == null ? "" : result.contents.get("text");
        com.biliwind.blog.service.ai.AiArticleResponseParser.Article parsed =
                com.biliwind.blog.service.ai.AiArticleResponseParser.parse(objectMapper, text);
        String title = parsed.title();
        String summary = parsed.summary();
        String content = parsed.contentMarkdown();

        User operator = currentOperator();
        OffsetDateTime now = OffsetDateTime.now();
        String slug = uniqueSlug(title, topicId);
        Map<String, String> titleMap = Map.of(effectiveLanguage, title);
        Map<String, String> summaryMap = Map.of(effectiveLanguage, summary);
        Map<String, String> contentMap = PostHelper.injectBlockIds(
                Map.of(effectiveLanguage, content));

        Post post = new Post();
        post.slug = slug;
        post.title = titleMap;
        post.summary = summaryMap;
        post.status = PostStatus.DRAFT;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.aiSummaryStatus = 2;
        post.user = operator;
        post.repostPolicyCode = repostPolicyCatalog.require(repostPolicyCode).code();
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

        articleExternalLinkService.syncMarkdownLinks(post, contentMap);
        auditService.log("post", post.id, "ai_article_draft", null,
                Map.of("topicId", topicId, "language", effectiveLanguage,
                        "provider", result == null || result.provider == null ? "AI_MANAGER" : result.provider));
        postSyncedEvent.fire(new PostSyncedEvent(post.id));

        String warning = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("topicId", topicId);
            payload.put("postId", post.id);
            payload.put("language", effectiveLanguage);
            payload.put("instructions", instructions == null ? "" : truncate(instructions, 4000));
            payload.put("repostPolicyCode", post.repostPolicyCode);
            payload.put("modelName", result == null || result.modelId == null ? "" : result.modelId);
            payload.put("provider", result == null || result.provider == null ? "AI_MANAGER" : result.provider);
            codexClient.topicCommand("topics.bindDraft", payload,
                            "windblog-admin:" + operator.id, UUID.randomUUID().toString())
                    .toCompletableFuture()
                    .join();
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            warning = "草稿已创建，但回写话题状态失败: " + safeMessage(cause);
        }

        return new DraftResult(post.id, slug, title, summary, content, effectiveLanguage, warning);
    }

    private JsonNode readTopic(Long topicId) {
        try {
            JsonNode response = codexClient.topicCommand("topics.read", Map.of("id", topicId),
                            "windblog-admin:" + actorId(), UUID.randomUUID().toString())
                    .toCompletableFuture()
                    .join();
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull() || data.isEmpty()) {
                throw new BadRequestException("话题不存在或 Codex Creator 未返回话题详情");
            }
            return data;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("读取话题失败，请确认 Codex Creator 连接正常: " + safeMessage(cause));
        }
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

    private User currentOperator() {
        Long userId = adminRequestContext.getUserId();
        User user = userId == null ? null
                : User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null) {
            throw new BadRequestException("当前管理员不存在或已禁用");
        }
        return user;
    }

    private Long actorId() {
        Long userId = adminRequestContext.getUserId();
        return userId == null ? 0L : userId;
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

    public record DraftResult(Long postId, String slug, String title, String summary,
                              String contentMarkdown, String language, String warning) {
    }
}
