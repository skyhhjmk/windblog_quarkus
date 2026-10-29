package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostResult;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationItem;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationPage;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiResult;
import com.biliwind.blog.service.ai.MarkdownTranslationGuard;
import com.biliwind.blog.service.ai.TranslationPrompt;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.link.ArticleExternalLinkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Translation management read model and one-click translation workflow.
 * <p>
 * Translated content lives in the existing multi-language JSON columns, so the
 * public renderer, cache snapshots and Edge replication keep one source of
 * truth. Every successful write creates a normal revision and fires the same
 * post-sync event used by the editor, which refreshes Redis and reindexes ES.
 */
@ApplicationScoped
public class AdminTranslationService {

    @Inject
    EntityManager entityManager;

    @Inject
    AiManager aiManager;

    @Inject
    MediaManagementService mediaService;

    @Inject
    ArticleExternalLinkService articleExternalLinkService;

    @Inject
    AuditService auditService;

    @Inject
    PostAiMetadataService postAiMetadataService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    Event<PostSyncedEvent> esSyncEvent;

    @Inject
    LanguageSettingsService languageSettingsService;

    @Transactional
    public TranslationPage list(Short status, String keyword, String translationStatus,
                                String targetLanguage, int page, int pageSize) {
        String target = canonicalLanguage(normalizeLanguage(
                targetLanguage == null || targetLanguage.isBlank() ? defaultTargetLanguage() : targetLanguage,
                "targetLanguage"));
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        StringBuilder where = new StringBuilder(
                " from posts p left join post_revisions r on r.id = p.current_revision_id"
                        + " where p.deleted_at is null");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (status != null) {
            where.append(" and p.status = :status");
            parameters.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (lower(p.slug) like :keyword or lower(p.title::text) like :keyword)");
            parameters.put("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }
        String translatedPredicate = " (coalesce(nullif(btrim(p.title ->> :targetLanguage), ''),"
                + " nullif(btrim(p.title ->> :targetLanguageLower), ''), '') <> ''"
                + " and coalesce(nullif(btrim(r.content_markdown ->> :targetLanguage), ''),"
                + " nullif(btrim(r.content_markdown ->> :targetLanguageLower), ''), '') <> '')";
        if ("translated".equalsIgnoreCase(translationStatus)) {
            where.append(" and").append(translatedPredicate);
            parameters.put("targetLanguage", target);
            parameters.put("targetLanguageLower", target.toLowerCase(Locale.ROOT));
        } else if ("missing".equalsIgnoreCase(translationStatus)) {
            where.append(" and not").append(translatedPredicate);
            parameters.put("targetLanguage", target);
            parameters.put("targetLanguageLower", target.toLowerCase(Locale.ROOT));
        }

        Query countQuery = entityManager.createNativeQuery("select count(*)" + where);
        Query selectQuery = entityManager.createNativeQuery(
                "select p.*" + where + " order by p.updated_at desc, p.id desc", Post.class);
        bind(countQuery, parameters);
        bind(selectQuery, parameters);
        long total = ((Number) countQuery.getSingleResult()).longValue();
        selectQuery.setFirstResult((safePage - 1) * safePageSize);
        selectQuery.setMaxResults(safePageSize);
        @SuppressWarnings("unchecked")
        List<Post> posts = selectQuery.getResultList();

        List<TranslationItem> items = new ArrayList<>();
        for (Post post : posts) {
            items.add(toItem(post, target));
        }
        return new TranslationPage(items, total, safePage, safePageSize, target);
    }

    @Transactional
    public TranslatePostResult translatePost(Long postId, String targetLanguage,
                                             String sourceLanguage, boolean overwrite) {
        Post post = postId == null ? null : Post.find("id = ?1 and deletedAt is null", postId).firstResult();
        if (post == null) {
            throw new NotFoundException("文章不存在");
        }
        PostRevision revision = post.currentRevision;
        if (revision == null || revision.contentMarkdown == null || revision.contentMarkdown.isEmpty()) {
            throw new BadRequestException("文章暂无草稿内容");
        }

        String target = canonicalLanguage(normalizeLanguage(
                targetLanguage == null || targetLanguage.isBlank() ? defaultTargetLanguage() : targetLanguage,
                "targetLanguage"));
        boolean alreadyTranslated = hasTranslation(post, target);
        if (alreadyTranslated && !overwrite) {
            return skipped(post, target, revision, "该文章已存在目标语言译本");
        }

        String source = resolveSourceLanguage(post, target, sourceLanguage);
        String sourceKey = findLanguageKey(revision.contentMarkdown, source);
        String sourceTitle = LanguageHelper.resolveLocalizedValue(post.title, source);
        String sourceSummary = post.summary == null ? null
                : LanguageHelper.resolveLocalizedValue(post.summary, source);
        String sourceContent = sourceKey == null ? null : revision.contentMarkdown.get(sourceKey);
        if (sourceTitle == null || sourceTitle.isBlank()) {
            throw new BadRequestException("找不到源语言标题: " + source);
        }
        if (sourceContent == null || sourceContent.isBlank()) {
            throw new BadRequestException("找不到源语言正文: " + source);
        }
        if (target.equalsIgnoreCase(source)) {
            throw new BadRequestException("源语言和目标语言不能相同");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", sourceTitle);
        fields.put("summary", sourceSummary == null ? "" : sourceSummary);
        fields.put("contentMarkdown", sourceContent);

        AiResult aiResult = requestTranslation(source, target, fields);
        String translatedTitle = required(aiResult, "title");
        String translatedSummary = optional(aiResult, "summary");
        String translatedContent = required(aiResult, "contentMarkdown");

        List<String> issues = MarkdownTranslationGuard.verify(sourceContent, translatedContent);
        if (!issues.isEmpty()) {
            aiResult = requestTranslation(source, target, fields, issues);
            translatedTitle = required(aiResult, "title");
            translatedSummary = optional(aiResult, "summary");
            translatedContent = required(aiResult, "contentMarkdown");
            issues = MarkdownTranslationGuard.verify(sourceContent, translatedContent);
            if (!issues.isEmpty()) {
                throw new BadRequestException("AI 译文未保持原文 Markdown 结构: " + String.join("；", issues));
            }
        }

        String targetKey = targetKeyForWrite(post, revision, target);
        Map<String, String> nextTitle = new LinkedHashMap<>(post.title == null ? Map.of() : post.title);
        nextTitle.put(targetKey, translatedTitle);
        Map<String, String> nextSummary = new LinkedHashMap<>(post.summary == null ? Map.of() : post.summary);
        if (!translatedSummary.isBlank()) {
            nextSummary.put(targetKey, translatedSummary);
        }
        Map<String, String> nextContent = new LinkedHashMap<>(revision.contentMarkdown);
        nextContent.put(targetKey, translatedContent);
        nextContent = PostHelper.injectBlockIds(nextContent);

        User operator = currentOperator();
        PostRevision nextRevision = new PostRevision();
        nextRevision.post = post;
        nextRevision.title = nextTitle;
        nextRevision.contentMarkdown = nextContent;
        nextRevision.editorType = revision.editorType;
        nextRevision.revisionNumber = nextRevisionNumber(post.id);
        nextRevision.createdBy = operator;
        nextRevision.createdAt = OffsetDateTime.now();
        nextRevision.persist();

        boolean publishNow = post.status == PostStatus.PUBLISHED
                && sameRevision(post.publishedRevision, revision);
        post.currentRevision = nextRevision;
        if (publishNow) {
            post.publishedRevision = nextRevision;
        }
        post.title = nextTitle;
        post.summary = nextSummary;
        post.updatedAt = OffsetDateTime.now();

        mediaService.syncPostReferences(post, nextContent);
        articleExternalLinkService.syncMarkdownLinks(post, nextContent);
        auditService.log("post", post.id, "ai_translation_saved", null,
                Map.of("sourceLanguage", source, "targetLanguage", target,
                        "revisionNumber", nextRevision.revisionNumber, "published", publishNow));
        postAiMetadataService.record(post.id, nextRevision.id, "translate", aiResult,
                publishNow ? "PUBLISHED" : "DRAFT");
        esSyncEvent.fire(new PostSyncedEvent(post.id));

        return new TranslatePostResult(post.id, post.slug, source, target, true, false,
                publishNow, nextRevision.revisionNumber, "翻译完成",
                translatedTitle, translatedSummary, translatedContent);
    }

    private AiResult requestTranslation(String source, String target, Map<String, String> fields) {
        return requestTranslation(source, target, fields, List.of());
    }

    private AiResult requestTranslation(String source, String target, Map<String, String> fields,
                                        List<String> previousIssues) {
        String promptTemplate = previousIssues.isEmpty() ? null
                : TranslationPrompt.DEFAULT_PROMPT + "\n上一次译文未通过结构校验："
                + String.join("；", previousIssues) + "。请仅修正结构问题并重新输出完整 JSON。\n";
        try {
            AiResult result = aiManager.translate(source, target, fields, promptTemplate)
                    .toCompletableFuture()
                    .join();
            if (result == null) {
                throw new BadRequestException("AI 未返回翻译结果");
            }
            return result;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("AI 翻译失败: " + safeMessage(cause));
        }
    }

    private TranslatePostResult skipped(Post post, String target, PostRevision revision, String message) {
        return new TranslatePostResult(post.id, post.slug, null, target, false, true,
                post.status == PostStatus.PUBLISHED,
                revision == null ? null : revision.revisionNumber, message,
                null, null, null);
    }

    private TranslationItem toItem(Post post, String target) {
        PostRevision revision = post.currentRevision;
        Map<String, String> content = revision == null || revision.contentMarkdown == null
                ? Map.of() : revision.contentMarkdown;
        Set<String> languages = new LinkedHashSet<>();
        addLanguages(languages, post.title);
        addLanguages(languages, post.summary);
        addLanguages(languages, content);
        List<String> sorted = new ArrayList<>(languages);
        sorted.sort(String::compareTo);
        String source = resolveSourceLanguage(post, target, null);
        return new TranslationItem(post.id, post.slug, post.title,
                post.status == null ? PostStatus.DRAFT.getCode() : post.status.getCode(),
                post.publishedRevision != null,
                sameRevision(post.publishedRevision, revision),
                revision == null ? 0 : revision.revisionNumber,
                sorted, source, hasTranslation(post, target), post.publishedAt, post.updatedAt);
    }

    private void addLanguages(Set<String> languages, Map<String, String> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                languages.add(entry.getKey());
            }
        }
    }

    private boolean hasTranslation(Post post, String target) {
        String title = LanguageHelper.lookupIgnoreCase(post.title, target);
        String content = post.currentRevision == null ? null
                : LanguageHelper.lookupIgnoreCase(post.currentRevision.contentMarkdown, target);
        return title != null && !title.isBlank() && content != null && !content.isBlank();
    }

    private String resolveSourceLanguage(Post post, String target, String requested) {
        if (requested != null && !requested.isBlank()) {
            String normalized = canonicalLanguage(normalizeLanguage(requested, "sourceLanguage"));
            if (!normalized.equalsIgnoreCase(target)) {
                return normalized;
            }
            throw new BadRequestException("源语言和目标语言不能相同");
        }
        String siteDefault = canonicalLanguage(languageSettingsService.defaultLanguage(null));
        String siteDefaultTitle = LanguageHelper.lookupIgnoreCase(post.title, siteDefault);
        String siteDefaultContent = post.currentRevision == null ? null
                : LanguageHelper.lookupIgnoreCase(post.currentRevision.contentMarkdown, siteDefault);
        if (!siteDefault.equalsIgnoreCase(target)
                && siteDefaultTitle != null && !siteDefaultTitle.isBlank()
                && siteDefaultContent != null && !siteDefaultContent.isBlank()) {
            return siteDefault;
        }
        Set<String> candidates = new LinkedHashSet<>();
        addLanguages(candidates, post.title);
        if (post.currentRevision != null) {
            addLanguages(candidates, post.currentRevision.contentMarkdown);
        }
        return candidates.stream()
                .filter(language -> !language.equalsIgnoreCase(target))
                .findFirst()
                .map(this::canonicalLanguage)
                .orElse(LanguageConstant.DEFAULT_LANG);
    }

    private String targetKeyForWrite(Post post, PostRevision revision, String target) {
        for (String key : languageKeys(post.title)) {
            if (key.equalsIgnoreCase(target)) {
                return key;
            }
        }
        if (revision.contentMarkdown != null) {
            for (String key : revision.contentMarkdown.keySet()) {
                if (key != null && key.equalsIgnoreCase(target)) {
                    return key;
                }
            }
        }
        return target;
    }

    private Set<String> languageKeys(Map<String, String> values) {
        return values == null ? Set.of() : values.keySet();
    }

    private String findLanguageKey(Map<String, String> values, String language) {
        if (values == null) {
            return null;
        }
        for (String key : values.keySet()) {
            if (key != null && key.equalsIgnoreCase(language)) {
                return key;
            }
        }
        return null;
    }

    private String required(AiResult result, String field) {
        String value = result == null || result.contents == null ? null : result.contents.get(field);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("AI 翻译结果缺少字段: " + field);
        }
        return value;
    }

    private String optional(AiResult result, String field) {
        String value = result == null || result.contents == null ? null : result.contents.get(field);
        return value == null ? "" : value;
    }

    public String defaultTargetLanguage() {
        com.biliwind.blog.model.SystemSetting setting =
                com.biliwind.blog.model.SystemSetting.findByKey("ai_translation");
        if (setting != null && setting.configValue != null) {
            String configured = setting.configValue.path("defaultTargetLanguage").asText("");
            if (!configured.isBlank()) {
                return configured.trim();
            }
        }
        return LanguageConstant.LANG_EN_US;
    }

    private String normalizeLanguage(String language, String field) {
        if (language == null || language.isBlank()) {
            throw new BadRequestException(field + " 不能为空");
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z]{2,8}(?:-[a-z0-9]{2,8})*$")) {
            throw new BadRequestException(field + " 必须是有效的语言代码");
        }
        return normalized;
    }

    private String canonicalLanguage(String language) {
        String supported = LanguageHelper.normalizeToSupportedLang(language);
        return supported == null ? language : supported;
    }

    private boolean sameRevision(PostRevision left, PostRevision right) {
        return left != null && right != null && left.id != null && left.id.equals(right.id);
    }

    private int nextRevisionNumber(Long postId) {
        PostRevision latest = PostRevision.find("post.id = ?1 order by revisionNumber desc", postId).firstResult();
        return latest == null ? 1 : latest.revisionNumber + 1;
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

    private void bind(Query query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
