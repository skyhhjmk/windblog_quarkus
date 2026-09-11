package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.helper.PostHelper;
import com.biliwind.blog.common.helper.SlugHelper;
import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.CodexCreatorDraftAssignment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostAiMetadata;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.CodexCreatorEventPublisher;
import com.biliwind.blog.service.MediaManagementService;
import com.biliwind.blog.service.PostAiMetadataService;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.link.ArticleExternalLinkService;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Bridges a Codex Creator article job into one WindBlog draft exactly once. */
@ApplicationScoped
public class CodexCreatorDraftService {
    private static final List<String> DECLARATIONS = List.of(
            "AI_GENERATED_CONTENT", "AUTOMATION_USE_ALLOWED");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Inject
    CacheService cacheService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Inject
    CodexCreatorHttpClient client;

    @Inject
    MediaManagementService mediaService;

    @Inject
    ArticleExternalLinkService articleExternalLinkService;

    @Inject
    PostAiMetadataService postAiMetadataService;

    @Inject
    CodexCreatorEventPublisher codexCreatorEventPublisher;

    @Inject
    Event<PostSyncedEvent> esSyncEvent;

    @Inject
    AuditService auditService;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    Instance<CodexCreatorDraftService> self;

    public CompletionStage<Map<String, Object>> start(Long topicId, Long categoryId,
                                                      String language, String instructions, String profileId,
                                                      Long operatorId, String traceId) {
        return start(topicId, categoryId, language, instructions, profileId, null, false, List.of(), operatorId, traceId);
    }

    public CompletionStage<Map<String, Object>> start(Long topicId, Long categoryId,
                                                      String language, String instructions, String profileId,
                                                      String reasoningEffort,
                                                      boolean requiresPracticalVerification, List<Long> testServerIds,
                                                      Long operatorId, String traceId) {
        validateRequest(topicId, categoryId, language, instructions, operatorId);
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedInstructions = instructions == null ? "" : instructions.trim();
        String normalizedProfileId = profileId == null ? "" : profileId.trim();
        String normalizedReasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim().toLowerCase(java.util.Locale.ROOT);
        String requestKey = requestKey(topicId, categoryId, normalizedLanguage,
                normalizedInstructions + "\nmodel=" + normalizedProfileId + "\neffort=" + normalizedReasoningEffort,
                requiresPracticalVerification, testServerIds);
        CodexCreatorDraftAssignment assignment;
        try {
            assignment = self.get().ensureAssignment(
                    topicId, categoryId, normalizedLanguage, normalizedInstructions, requestKey, operatorId);
        } catch (RuntimeException exception) {
            CodexCreatorDraftAssignment winner = self.get().findByRequestKey(requestKey);
            if (winner == null) throw exception;
            if (!matches(winner, topicId, categoryId, normalizedLanguage, normalizedInstructions)) {
                throw new BadRequestException("草稿幂等键已绑定到其他请求");
            }
            assignment = winner;
        }

        if (assignment.postId != null || "DRAFT_CREATED".equals(assignment.status)) {
            return CompletableFuture.completedFuture(view(assignment));
        }

        final CodexCreatorDraftAssignment assignmentForRequest = assignment;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topicId", topicId);
        payload.put("categoryId", categoryId);
        payload.put("language", normalizedLanguage);
        payload.put("instructions", normalizedInstructions);
        payload.put("requestKey", requestKey);
        payload.put("requiresPracticalVerification", requiresPracticalVerification);
        payload.put("testServerIds", testServerIds == null ? List.of()
                : testServerIds.stream().filter(Objects::nonNull).distinct().sorted().toList());
        if (!normalizedProfileId.isBlank()) payload.put("profileId", normalizedProfileId);
        if (!normalizedReasoningEffort.isBlank()) payload.put("reasoningEffort", normalizedReasoningEffort);
        return client.topicCommand("article.start", payload,
                        "windblog-admin:" + operatorId, traceId)
                .thenCompose(response -> {
                    JsonNode data = data(response);
                    requireRemoteJobId(data);
                    self.get().recordRemoteJob(assignmentForRequest.id, data);
                    String status = data.path("status").asText("");
                    if ("SUCCEEDED".equals(status)) return refresh(assignmentForRequest.id, operatorId, traceId);
                    if ("FAILED".equals(status)) {
                        self.get().markFailed(assignmentForRequest.id, data.path("error").asText("article job failed"));
                    }
                    return CompletableFuture.completedFuture(self.get().viewWithExecution(assignmentForRequest.id, data));
                })
                .exceptionallyCompose(error -> {
                    self.get().markFailed(assignmentForRequest.id, rootMessage(error));
                    return CompletableFuture.failedFuture(unwrap(error));
                });
    }

    public CompletionStage<Map<String, Object>> regenerate(Long topicId, String profileId, String reasoningEffort,
                                                            Long operatorId, String traceId) {
        if (topicId == null || topicId <= 0) throw new BadRequestException("话题不能为空");
        if (operatorId == null) throw new jakarta.ws.rs.WebApplicationException("未登录", 401);
        CodexCreatorDraftAssignment assignment = self.get().prepareRegeneration(topicId, operatorId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", assignment.codexJobId);
        if (profileId != null && !profileId.isBlank()) payload.put("profileId", profileId.trim());
        if (reasoningEffort != null && !reasoningEffort.isBlank()) payload.put("reasoningEffort", reasoningEffort.trim().toLowerCase(java.util.Locale.ROOT));
        return client.topicCommand("article.regenerate", payload,
                        "windblog-admin:" + operatorId, traceId)
                .thenApply(response -> {
                    JsonNode data = data(response);
                    requireRemoteJobId(data);
                    self.get().recordRemoteJob(assignment.id, data);
                    return self.get().viewWithExecution(assignment.id, data);
                })
                .exceptionallyCompose(error -> {
                    self.get().markFailed(assignment.id, rootMessage(error));
                    return CompletableFuture.failedFuture(unwrap(error));
                });
    }

    public CompletionStage<Map<String, Object>> refresh(Long assignmentId, Long operatorId, String traceId) {
        CodexCreatorDraftAssignment assignment = self.get().loadAssignment(assignmentId);
        if ("DRAFT_CREATED".equals(assignment.status)) {
            return CompletableFuture.completedFuture(view(assignment));
        }
        if (assignment.codexJobId == null) return CompletableFuture.completedFuture(view(assignment));
        Map<String, Object> payload = Map.of("id", assignment.codexJobId);
        return client.topicCommand("article.read", payload,
                        "windblog-admin:" + operatorId, traceId)
                .thenCompose(response -> {
                    JsonNode data = data(response);
                    requireRemoteJobId(data);
                    self.get().recordRemoteJob(assignment.id, data);
                    String status = data.path("status").asText("");
                    if ("FAILED".equals(status)) {
                        self.get().markFailed(assignment.id, data.path("error").asText("article job failed"));
                        return CompletableFuture.completedFuture(self.get().viewById(assignment.id));
                    }
                    if (!"SUCCEEDED".equals(status)) {
                        return CompletableFuture.completedFuture(self.get().viewWithExecution(assignment.id, data));
                    }
                    try {
                        Map<String, Object> created = self.get().finalizeDraft(assignment.id, data);
                        Long postId = number(created.get("postId"));
                        return acknowledgeRemoteDraft(assignment.codexJobId, postId, assignment,
                                        operatorId, traceId)
                                .thenApply(ignored -> created);
                    } catch (RuntimeException exception) {
                        self.get().markFailed(assignment.id, rootMessage(exception));
                        if (assignment.postId == null) {
                            return CompletableFuture.failedFuture(exception);
                        }
                        return acknowledgeRemoteDraft(assignment.codexJobId, assignment.postId, assignment,
                                        operatorId, traceId)
                                .thenCompose(ignored -> CompletableFuture.failedFuture(exception));
                    }
                });
    }

    @Transactional
    public Map<String, Object> viewById(Long id) {
        return view(findAssignment(id));
    }

    @Transactional
    Map<String, Object> viewWithExecution(Long id, JsonNode remoteJob) {
        Map<String, Object> view = view(findAssignment(id));
        JsonNode execution = remoteJob == null ? null : remoteJob.get("execution");
        if (execution != null && execution.isObject()) {
            view.put("execution", objectMapper.convertValue(execution, Map.class));
        }
        return view;
    }

    @Transactional
    CodexCreatorDraftAssignment loadAssignment(Long id) {
        return findAssignment(id);
    }

    @Transactional
    CodexCreatorDraftAssignment prepareRegeneration(Long topicId, Long operatorId) {
        CodexCreatorDraftAssignment assignment = CodexCreatorDraftAssignment.<CodexCreatorDraftAssignment>find(
                        "topicId = ?1 order by createdAt desc", topicId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (assignment == null || assignment.codexJobId == null || assignment.postId == null) {
            throw new BadRequestException("该话题没有可重新生成的草稿任务");
        }
        if (!"DRAFT_CREATED".equals(assignment.status)) {
            throw new BadRequestException("草稿正在生成，请等待当前任务完成");
        }
        Post post = Post.find("id = ?1", assignment.postId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (post == null) throw new NotFoundException("原草稿不存在");
        if (post.status != PostStatus.DRAFT) {
            throw new BadRequestException("仅草稿状态的文章可以重新生成");
        }
        assignment.status = "REGENERATING";
        assignment.errorMessage = null;
        assignment.updatedAt = OffsetDateTime.now();
        auditService.log("post", post.id, "codex_creator_draft_regenerate_requested", null,
                Map.of("topicId", topicId, "codexJobId", assignment.codexJobId), null, operatorId);
        return assignment;
    }

    @Transactional
    CodexCreatorDraftAssignment ensureAssignment(Long topicId, Long categoryId, String language,
                                                 String instructions, String requestKey, Long operatorId) {
        CodexCreatorDraftAssignment existing = CodexCreatorDraftAssignment.find(
                "requestKey", requestKey).firstResult();
        if (existing != null) {
            if (!Objects.equals(existing.topicId, topicId)
                    || (categoryId != null && !Objects.equals(existing.categoryId, categoryId))
                    || !Objects.equals(existing.language, language)
                    || !Objects.equals(existing.instructions == null ? "" : existing.instructions, instructions)) {
                throw new BadRequestException("草稿幂等键已绑定到其他请求");
            }
            return existing;
        }
        if (categoryId != null && Category.findById(categoryId) == null) {
            throw new BadRequestException("分类不存在");
        }
        CodexCreatorDraftAssignment assignment = new CodexCreatorDraftAssignment();
        assignment.topicId = topicId;
        assignment.categoryId = categoryId;
        assignment.language = language;
        assignment.instructions = instructions;
        assignment.requestKey = requestKey;
        assignment.createdBy = operatorId;
        assignment.status = "REQUESTED";
        assignment.createdAt = OffsetDateTime.now();
        assignment.updatedAt = assignment.createdAt;
        assignment.persist();
        return assignment;
    }

    @Transactional
    CodexCreatorDraftAssignment findByRequestKey(String requestKey) {
        return CodexCreatorDraftAssignment.find("requestKey", requestKey).firstResult();
    }

    @Transactional
    void recordRemoteJob(Long assignmentId, JsonNode data) {
        CodexCreatorDraftAssignment assignment = findAssignment(assignmentId);
        assignment.codexJobId = data.hasNonNull("id") ? data.path("id").asLong() : assignment.codexJobId;
        assignment.codexTaskId = data.hasNonNull("taskId") ? data.path("taskId").asLong() : assignment.codexTaskId;
        String remoteStatus = data.path("status").asText("");
        assignment.status = switch (remoteStatus) {
            case "SUCCEEDED" -> "READY";
            case "FAILED" -> "FAILED";
            default -> "GENERATING";
        };
        assignment.errorMessage = "FAILED".equals(remoteStatus)
                ? data.path("error").asText("article job failed") : null;
        assignment.updatedAt = OffsetDateTime.now();
    }

    @Transactional
    Map<String, Object> finalizeDraft(Long assignmentId, JsonNode jobData) {
        CodexCreatorDraftAssignment assignment = CodexCreatorDraftAssignment.find("id", assignmentId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (assignment == null) throw new NotFoundException("草稿任务不存在");
        boolean regeneration = assignment.postId != null;
        JsonNode content = jobData.path("content");
        String title = text(content, "title", 160, true);
        String summary = text(content, "summary", 2_000, false);
        String markdown = text(content, "contentMarkdown", 100_000, true);
        JsonNode provenance = jobData.path("provenance");
        requireQualityContract(jobData);
        User operator = User.find("id = ?1 and status = 1 and deletedAt is null", assignment.createdBy).firstResult();
        if (operator == null) throw new jakarta.ws.rs.WebApplicationException("管理员不存在或已禁用", 401);
        Category category = resolveCategory(assignment, jobData);

        OffsetDateTime now = OffsetDateTime.now();
        Map<String, String> titleMap = Map.of(assignment.language, title);
        Map<String, String> summaryMap = summary.isBlank() ? Map.of() : Map.of(assignment.language, summary);
        Post post;
        if (regeneration) {
            post = Post.find("id = ?1", assignment.postId)
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            if (post == null) throw new NotFoundException("原草稿不存在");
            if (post.status != PostStatus.DRAFT) {
                throw new BadRequestException("原文章已不再是草稿，拒绝覆盖其当前修订");
            }
        } else {
            post = new Post();
            post.slug = uniqueSlug(title, assignment.id);
            post.status = PostStatus.DRAFT;
            post.visibility = 0;
            post.renderType = PostRenderType.MARKDOWN;
            post.user = operator;
            post.createdAt = now;
            post.contentDeclarations = DECLARATIONS;
            post.repostPolicyCode = repostPolicyCatalog.require(null).code();
        }
        String modelName = resolveModelName(jobData, provenance);
        post.authorName = modelName;
        post.title = titleMap;
        post.summary = summaryMap;
        post.aiSummary = summaryMap;
        post.aiSummaryStatus = 1;
        post.category = category;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = titleMap;
        revision.contentMarkdown = PostHelper.injectBlockIds(Map.of(assignment.language, markdown));
        revision.editorType = 0;
        revision.revisionNumber = regeneration ? nextRevisionNumber(post.id) : 1;
        revision.createdBy = operator;
        revision.createdAt = now;
        revision.persist();
        post.currentRevision = revision;
        post.updatedAt = now;
        mediaService.syncPostReferences(post, revision.contentMarkdown);
        articleExternalLinkService.syncMarkdownLinks(post, revision.contentMarkdown);

        AiResult result = new AiResult();
        result.provider = "CODEX_CREATOR";
        result.taskId = jobData.hasNonNull("taskId") ? jobData.path("taskId").asText() : null;
        result.modelId = resolveModelId(jobData, provenance);
        result.reasoningEffort = provenance.path("reasoningEffort").asText(null);
        result.generationMode = regeneration ? "MANUAL_REGENERATION" : "MANUAL_ASSIGNMENT";
        result.provenance = provenanceMap(provenance, assignment, jobData);
        postAiMetadataService.record(post.id, revision.id, "article", result, "DRAFT");

        assignment.postId = post.id;
        assignment.status = "DRAFT_CREATED";
        assignment.errorMessage = null;
        assignment.updatedAt = now;
        esSyncEvent.fire(new PostSyncedEvent(post.id));
        codexCreatorEventPublisher.postRevisionUpdated(post.id, revision.id,
                Map.of("title", titleMap, "contentMarkdown", revision.contentMarkdown), null);
        auditService.log("post", post.id,
                regeneration ? "codex_creator_draft_regenerated" : "codex_creator_draft_created", null,
                Map.of("topicId", assignment.topicId, "codexJobId", assignment.codexJobId), null,
                assignment.createdBy);
        return view(assignment);
    }

    @Transactional
    void markFailed(Long assignmentId, String message) {
        CodexCreatorDraftAssignment assignment = CodexCreatorDraftAssignment.findById(assignmentId);
        if (assignment == null) return;
        assignment.status = assignment.postId == null ? "FAILED" : "DRAFT_CREATED";
        assignment.errorMessage = message == null ? "草稿任务失败" : message;
        assignment.updatedAt = OffsetDateTime.now();
    }

    private CodexCreatorDraftAssignment findAssignment(Long id) {
        CodexCreatorDraftAssignment assignment = CodexCreatorDraftAssignment.findById(id);
        if (assignment == null) throw new NotFoundException("草稿任务不存在");
        return assignment;
    }

    private CompletionStage<Map<String, Object>> acknowledgeRemoteDraft(
            Long jobId, Long postId, CodexCreatorDraftAssignment assignment,
            Long operatorId, String traceId) {
        if (jobId == null || postId == null) {
            return CompletableFuture.completedFuture(view(assignment));
        }
        return client.topicCommand("article.acknowledge",
                        Map.of("id", jobId, "postId", postId),
                        "windblog-admin:" + operatorId, traceId)
                .handle((ignored, acknowledgeError) -> {
                    if (acknowledgeError != null) {
                        auditService.log("codex_creator", jobId,
                                "draft_acknowledge_retry", null,
                                Map.of("postId", postId, "error", rootMessage(acknowledgeError)));
                    }
                    return self.get().viewById(assignment.id);
                });
    }

    private boolean matches(CodexCreatorDraftAssignment assignment, Long topicId, Long categoryId,
                            String language, String instructions) {
        return Objects.equals(assignment.topicId, topicId)
                && (categoryId == null || Objects.equals(assignment.categoryId, categoryId))
                && Objects.equals(assignment.language, language)
                && Objects.equals(assignment.instructions == null ? "" : assignment.instructions, instructions);
    }

    private void requireRemoteJobId(JsonNode data) {
        JsonNode id = data == null ? null : data.get("id");
        if (id == null || !id.canConvertToLong() || id.asLong() <= 0) {
            throw new IllegalStateException("Codex Creator article job response has no valid id");
        }
    }

    private Map<String, Object> view(CodexCreatorDraftAssignment assignment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", assignment.id);
        view.put("topicId", assignment.topicId);
        view.put("categoryId", assignment.categoryId);
        view.put("language", assignment.language);
        view.put("instructions", assignment.instructions == null ? "" : assignment.instructions);
        view.put("status", assignment.status);
        view.put("codexJobId", assignment.codexJobId);
        view.put("codexTaskId", assignment.codexTaskId);
        view.put("postId", assignment.postId);
        view.put("error", assignment.errorMessage == null ? "" : assignment.errorMessage);
        view.put("createdAt", assignment.createdAt);
        view.put("updatedAt", assignment.updatedAt);
        return view;
    }

    private void validateRequest(Long topicId, Long categoryId, String language,
                                 String instructions, Long operatorId) {
        if (topicId == null || topicId <= 0) throw new BadRequestException("话题不能为空");
        if (categoryId != null && categoryId <= 0) throw new BadRequestException("分类无效");
        if (operatorId == null) throw new jakarta.ws.rs.WebApplicationException("未登录", 401);
        if (instructions != null && instructions.length() > 4_000) throw new BadRequestException("写作要求过长");
        normalizeLanguage(language);
    }

    /**
     * Resolve the category only when the article is ready to become a WindBlog
     * post.  This lets Codex choose/create a category during writing while
     * keeping the parent-side fallback deterministic when it does not return
     * a usable category id.
     */
    private Category resolveCategory(CodexCreatorDraftAssignment assignment, JsonNode jobData) {
        if (assignment.categoryId != null) {
            Category requested = Category.findById(assignment.categoryId);
            if (requested == null) throw new BadRequestException("分类不存在");
            return requested;
        }

        Long remoteCategoryId = positiveLong(jobData, "targetCategoryId");
        if (remoteCategoryId == null) {
            remoteCategoryId = positiveLong(jobData.path("content"), "categoryId");
        }
        Category category = remoteCategoryId == null ? null : Category.findById(remoteCategoryId);
        if (category == null) category = ensureUncategorized();
        assignment.categoryId = category.id;
        assignment.updatedAt = OffsetDateTime.now();
        return category;
    }

    /** Create the shared fallback once, safely across multiple WindBlog instances. */
    private Category ensureUncategorized() {
        Category existing = Category.find("slug", "uncategorized").firstResult();
        if (existing != null) return existing;

        entityManager.flush();
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO categories
                    (parent_id, slug, name, description, created_at, updated_at, post_count)
                VALUES
                    (NULL, 'uncategorized', CAST(:name AS jsonb), CAST(:description AS jsonb),
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                ON CONFLICT (slug) DO NOTHING
                """)
                .setParameter("name", "{\"zh-CN\":\"未分类\"}")
                .setParameter("description", "{\"zh-CN\":\"未指定分类时的默认分类\"}")
                .executeUpdate();
        Category category = Category.find("slug", "uncategorized").firstResult();
        if (category == null) throw new IllegalStateException("无法创建未分类默认分类");
        if (inserted > 0) {
            auditService.log("category", category.id, "create", null,
                    Map.of("source", "CODEX_CREATOR", "operation", "category.create",
                            "fallback", true));
            dataSyncEvent.fire(new DataSyncEvent("CATEGORY", category.id, "UPSERT"));
            cacheService.delete(CacheService.Keys.ALL_CATEGORIES);
            cacheService.deletePattern(CacheService.Keys.SIDEBAR_CATEGORIES + "*");
            cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
        }
        return category;
    }

    private static Long positiveLong(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong() || value.asLong() <= 0) return null;
        return value.asLong();
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) throw new BadRequestException("语言不能为空");
        String normalized = language.trim().replace('_', '-').toLowerCase();
        if (!normalized.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) {
            throw new BadRequestException("语言代码无效");
        }
        return normalized;
    }

    static String requestKey(Long topicId, Long categoryId, String language, String instructions,
                             boolean practicalVerification, List<Long> testServerIds) {
        String value = topicId + "\n" + categoryId + "\n" + language + "\n" + instructions.replaceAll("\\s+", " ");
        if (practicalVerification) {
            List<Long> servers = testServerIds == null ? List.of()
                    : testServerIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
            value += "\nverification=true\nservers=" + servers;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成草稿幂等键", exception);
        }
    }

    private String uniqueSlug(String title, Long assignmentId) {
        String base = SlugHelper.slugify(title);
        if (base.isBlank()) base = "codex-draft";
        base = base.substring(0, Math.min(140, base.length()));
        String candidate = base;
        if (Post.count("slug = ?1", candidate) > 0) {
            candidate = base + "-ai-" + assignmentId;
        }
        int suffix = 2;
        while (Post.count("slug = ?1", candidate) > 0) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 160 - tail.length())) + tail;
        }
        return candidate;
    }

    private int nextRevisionNumber(Long postId) {
        PostRevision latest = PostRevision.find("post.id = ?1 order by revisionNumber desc", postId).firstResult();
        return latest == null ? 1 : latest.revisionNumber + 1;
    }

    private Map<String, Object> provenanceMap(JsonNode provenance,
                                               CodexCreatorDraftAssignment assignment,
                                               JsonNode jobData) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (provenance != null && provenance.isObject()) {
            provenance.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        }
        result.put("modelName", resolveModelName(jobData, provenance));
        result.put("topicId", assignment.topicId);
        result.put("codexJobId", assignment.codexJobId);
        result.put("codexTaskId", assignment.codexTaskId);
        if (jobData.path("content").has("sources")) result.put("sources", jobData.path("content").get("sources"));
        if (jobData.has("qualityReport")) result.put("qualityReport", jobData.get("qualityReport"));
        if (jobData.has("qualityContractVersion")) {
            result.put("qualityContractVersion", jobData.get("qualityContractVersion"));
        }
        if (jobData.has("promptVersion")) result.put("promptVersion", jobData.get("promptVersion"));
        return result;
    }

    private String resolveModelName(JsonNode jobData, JsonNode provenance) {
        String value = textValue(jobData, "modelName");
        if (value == null) value = textValue(provenance, "modelName");
        if (value == null) value = textValue(jobData, "modelId");
        if (value == null) value = textValue(provenance, "model");
        return value == null ? "AI" : value;
    }

    private String resolveModelId(JsonNode jobData, JsonNode provenance) {
        String value = textValue(provenance, "model");
        if (value == null) value = textValue(jobData, "modelId");
        return value == null ? "codex-default" : value;
    }

    private String textValue(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText().trim();
    }

    private void requireQualityContract(JsonNode jobData) {
        int version = jobData.path("qualityContractVersion").asInt(0);
        if (version < 2) return; // Existing completed jobs remain importable but are marked as legacy provenance.
        JsonNode report = jobData.path("qualityReport");
        if (!report.isObject() || !report.path("passed").asBoolean(false)) {
            throw new BadRequestException("AI 草稿未通过内容质量门槛，已拒绝创建文章");
        }
    }

    private String text(JsonNode object, String field, int maxLength, boolean required) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            if (required) throw new BadRequestException("AI 草稿缺少 " + field);
            return "";
        }
        String text = value.asText().trim();
        if (required && text.isBlank()) throw new BadRequestException("AI 草稿缺少 " + field);
        if (text.length() > maxLength) throw new BadRequestException("AI 草稿 " + field + " 过长");
        return text;
    }

    private JsonNode data(JsonNode response) {
        if (response == null || !response.path("success").asBoolean(false)) {
            throw new IllegalStateException(response == null ? "Codex Creator 无响应" :
                    response.path("message").asText("Codex Creator 请求失败"));
        }
        return response.path("data");
    }

    private static Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) return error.getCause();
        return error;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = unwrap(error);
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null || current.getMessage() == null ? "草稿任务失败" : current.getMessage();
    }
}
