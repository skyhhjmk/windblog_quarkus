package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.*;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.MediaManagementService;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.event.Event;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.time.OffsetDateTime;
import java.util.*;

@Path("/api/admin/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "AdminPost")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminPostApiController {

    private static final Set<String> CONTENT_DECLARATION_CODES = Set.of(
            "EXPLICIT_AND_EMBEDDED_ADVERTISING",
            "AI_GENERATED_CONTENT",
            "SUBJECTIVE_VIEWPOINTS",
            "AUTOMATION_USE_ALLOWED",
            "CC_BY_NC_4_0");

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    com.biliwind.blog.service.ReliableAiTaskService reliableAiTaskService;

    @Inject
    com.biliwind.blog.service.CodexCreatorEventPublisher codexCreatorEventPublisher;

    @Inject
    com.biliwind.blog.service.ai.AiManager aiManager;

    @Inject
    com.biliwind.blog.service.PostAiMetadataService postAiMetadataService;

    @Inject
    MediaManagementService mediaService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    Event<Post> postEvent;

    @Inject
    jakarta.enterprise.event.Event<PostSyncedEvent> esSyncEvent;

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @Inject
    com.biliwind.blog.service.PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.service.ContentAccessTicketService contentAccessTicketService;

    @Inject
    com.biliwind.blog.repository.PostRepository postRepository;

    @Inject
    com.biliwind.blog.service.RegionValidationService regionValidationService;

    @Inject
    com.biliwind.blog.common.security.PasswordHasher passwordHasher;

    @Inject
    com.biliwind.blog.service.link.ArticleExternalLinkService articleExternalLinkService;

    @Inject
    com.biliwind.blog.service.ArticleEmailNotificationService articleEmailNotificationService;

    @Inject
    com.biliwind.blog.service.UserNotificationService userNotificationService;

    @POST
    @Path("/{id}/ai-summary/trigger")
    @Transactional
    @Operation(summary = "手动触发 AI 摘要生成")
    @APIResponse(responseCode = "200", description = "触发成功")
    @APIResponse(responseCode = "400", description = "当前状态不允许或无内容")
    public Response triggerAiSummary(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        if (post.aiSummaryStatus != null && post.aiSummaryStatus > 0) {
            throw badRequest("当前文章 AI 摘要已被锁定或禁用，无法手动触发");
        }

        PostRevision rev = post.currentRevision;
        if (rev == null || rev.contentMarkdown == null || rev.contentMarkdown.isEmpty()) {
            throw badRequest("文章暂无内容");
        }

        Long userId = adminRequestContext.getUserId();
        reliableAiTaskService.enqueueSummary(new com.biliwind.blog.service.ai.AiSummaryTask(
                post.id,
                com.biliwind.blog.common.helper.PostHelper.prepareContentForAi(rev.contentMarkdown),
                1, // Medium priority
                userId
        ));

        return Response.ok(java.util.Map.of("success", true, "message", "摘要任务已触发")).build();
    }

    @GET
    @Transactional
    @Operation(summary = "分页查询文章")
    @APIResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PageResult.class)))
    public PageResult<AdminPostItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize,
            @QueryParam("status") Short status,
            @QueryParam("categoryId") Long categoryId,
            @QueryParam("keyword") String keyword,
            @QueryParam("sortBy") @DefaultValue("id") String sortBy) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        io.quarkus.hibernate.orm.panache.PanacheQuery<Post> query = postRepository.findAdminPosts(
                status, categoryId, keyword, sortBy);
        long total = query.count();
        List<Post> entities = query.page(Page.of(safePage - 1, safePageSize)).list();

        List<AdminPostItem> items = new ArrayList<>();
        for (Post post : entities) {
            items.add(toItem(post));
        }
        return new PageResult<>(items, total, safePage, safePageSize);
    }

    @GET
    @Path("/{id}")
    @Transactional
    @Operation(summary = "查询文章详情")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public AdminPostDetail detail(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        return toDetail(post);
    }

    @GET
    @Path("/{id}/ai-metadata")
    @Transactional
    @Operation(summary = "查询文章 AI 来源和生成记录")
    public List<Map<String, Object>> aiMetadata(@PathParam("id") Long id) {
        mustFindPost(id);
        List<PostAiMetadata> metadata = PostAiMetadata.list("postId = ?1 order by createdAt desc", id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PostAiMetadata item : metadata) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", item.id);
            view.put("postId", item.postId);
            view.put("revisionId", item.revisionId);
            view.put("taskId", item.taskId);
            view.put("operation", item.operation);
            view.put("provider", item.provider);
            view.put("modelId", item.modelId);
            view.put("reasoningEffort", item.reasoningEffort);
            view.put("generationMode", item.generationMode);
            view.put("provenance", item.provenance);
            view.put("autoPublishStatus", item.autoPublishStatus);
            view.put("createdAt", item.createdAt);
            result.add(view);
        }
        return result;
    }

    @POST
    @Transactional
    @Operation(summary = "创建文章草稿")
    @APIResponse(responseCode = "200", description = "创建成功")
    @APIResponse(responseCode = "409", description = "slug 冲突")
    public AdminPostDetail create(@Valid PostCreateRequest request) {
        String slug = request.slug();
        if (slug == null || slug.isBlank()) {
            slug = com.biliwind.blog.common.helper.SlugHelper.slugify(request.title());
        } else {
            slug = slug.trim();
        }

        if (Post.count("slug = ?1", slug) > 0) {
            throw conflict("slug 已存在");
        }

        User operator = mustFindOperator();
        OffsetDateTime now = OffsetDateTime.now();

        Post post = new Post();
        post.slug = slug;
        post.title = request.title();
        post.summary = request.summary();
        post.aiSummary = request.aiSummary();
        PostStatus requestedStatus = resolveStatus(request.status(), PostStatus.DRAFT);
        post.status = PostStatus.DRAFT;
        post.visibility = request.visibility() == null ? 0 : request.visibility();
        post.password = resolveCreatedPassword(post.visibility, request.password());
        post.seoTitle = request.seoTitle();
        post.seoKeywords = request.seoKeywords();
        post.seoDescription = request.seoDescription();
        post.renderType = resolveRenderType(request.renderType());
        post.aiSummaryStatus = request.aiSummaryStatus() == null ? 0 : request.aiSummaryStatus();
        post.user = operator;
        post.createdAt = now;
        post.updatedAt = now;
        post.visibilityRegions = regionValidationService.validateAndFilterRegions(request.visibilityRegions());
        post.contentDeclarations = validateContentDeclarations(request.contentDeclarations());
        post.repostPolicyCode = repostPolicyCatalog.require(request.repostPolicyCode()).code();

        List<Long> initialCategoryIds = request.categoryIds() != null
                ? request.categoryIds()
                : (request.categoryId() == null ? List.of() : List.of(request.categoryId()));
        applyPostCategories(post, initialCategoryIds);

        post.persist();

        // 更新买断价格和免费行数
        com.biliwind.blog.common.helper.PostHelper.updateExtraInfo(post, request.pointsPrice(), request.freeLines());

        // 如果状态为自动(0)，则触发 AI 摘要任务
        if (post.aiSummaryStatus == 0 && request.contentMarkdown() != null && !request.contentMarkdown().isEmpty()) {
        reliableAiTaskService.enqueueSummary(new com.biliwind.blog.service.ai.AiSummaryTask(
                    post.id,
                    com.biliwind.blog.common.helper.PostHelper.prepareContentForAi(request.contentMarkdown()),
                    1,
                    operator.id
            ));
        }

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = request.title();
        revision.contentMarkdown = com.biliwind.blog.common.helper.PostHelper.injectBlockIds(request.contentMarkdown());
        revision.editorType = request.editorType() == null ? 0 : request.editorType();
        revision.revisionNumber = 1;
        revision.createdBy = operator;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        mediaService.syncPostReferences(post, revision.contentMarkdown);
        articleExternalLinkService.syncMarkdownLinks(post, revision.contentMarkdown);

        // 设置标签关联
        if (request.tagIds() != null && !request.tagIds().isEmpty()) {
            for (Long tagId : request.tagIds()) {
                com.biliwind.blog.model.Tag tag = com.biliwind.blog.model.Tag.findById(tagId);
                if (tag != null) {
                    PostTag postTag = new PostTag();
                    postTag.id = new PostTagId(post.id, tag.id);
                    postTag.post = post;
                    postTag.tag = tag;
                    postTag.persist();
                }
            }
        }

        if (requestedStatus == PostStatus.PUBLISHED) {
            publishRevision(post, revision, now);
        }

        esSyncEvent.fire(new PostSyncedEvent(post.id));
        codexCreatorEventPublisher.postRevisionUpdated(post.id, revision.id,
                Map.of("title", request.title(), "contentMarkdown", request.contentMarkdown() == null ? "" : request.contentMarkdown()), null);
        if (requestedStatus == PostStatus.PUBLISHED) {
            codexCreatorEventPublisher.postPublished(post.id, Map.of("postId", post.id, "title", request.title()), null);
        }

        return toDetail(post);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "编辑文章")
    @APIResponse(responseCode = "200", description = "更新成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    @APIResponse(responseCode = "409", description = "版本冲突或 slug 冲突")
    public AdminPostDetail update(@PathParam("id") Long id,
                                  @Valid PostUpdateRequest request) {
        Post post = mustFindPost(id);
        if (!post.version.equals(request.version())) {
            throw conflict("版本冲突，请刷新后重试");
        }

        boolean changed = false;

        changed |= updateBasicFields(post, request);
        changed |= updateCategories(post, request);

        // 更新买断价格和免费行数
        com.biliwind.blog.common.helper.PostHelper.updateExtraInfo(post, request.pointsPrice(), request.freeLines());
        changed = true; // extraInfo 变更标记为已更改

        if (request.tagIds() != null) {
            changed |= updatePostTags(post, request.tagIds());
        }

        changed |= updateContent(post, request);

        if (!changed) {
            throw conflict("内容相同");
        }

        post.updatedAt = OffsetDateTime.now();

        // 如果状态为自动(0)且内容已更变，则触发 AI 摘要任务
        if (post.aiSummaryStatus == 0) {
            PostRevision rev = post.currentRevision;
            if (rev != null && rev.contentMarkdown != null && !rev.contentMarkdown.isEmpty()) {
                Long userId = adminRequestContext.getUserId();
        reliableAiTaskService.enqueueSummary(new com.biliwind.blog.service.ai.AiSummaryTask(
                        post.id,
                        com.biliwind.blog.common.helper.PostHelper.prepareContentForAi(rev.contentMarkdown),
                        1,
                        userId
                ));
            }
        }

        esSyncEvent.fire(new PostSyncedEvent(post.id));
        if (post.currentRevision != null) {
            Map<String, Object> revisionInput = new LinkedHashMap<>();
            revisionInput.put("title", post.currentRevision.title == null ? "" : post.currentRevision.title);
            revisionInput.put("contentMarkdown", post.currentRevision.contentMarkdown == null ? "" : post.currentRevision.contentMarkdown);
            codexCreatorEventPublisher.postRevisionUpdated(post.id, post.currentRevision.id, revisionInput, null);
        }
        return toDetail(post);
    }

    @POST
    @Path("/{id}/publish")
    @Transactional
    @Operation(summary = "发布文章")
    @APIResponse(responseCode = "200", description = "发布成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    @APIResponse(responseCode = "409", description = "内容相同")
    public AdminPostDetail publish(@PathParam("id") Long id, EmailDispatchRequest emailDispatchRequest) {
        Post post = mustFindPost(id);
        boolean wasPendingReview = post.status == PostStatus.PENDING_REVIEW;
        if (post.currentRevision == null) {
            throw badRequest("文章暂无草稿内容");
        }
        if (post.status == PostStatus.PUBLISHED && sameRevision(post.publishedRevision, post.currentRevision)) {
            throw conflict("内容相同");
        }
        publishRevision(post, post.currentRevision, OffsetDateTime.now());
        if (wasPendingReview) {
            userNotificationService.notifyPostPublished(post);
        }
        articleEmailNotificationService.queueArticleUpdate(post, emailDispatchRequest);
        esSyncEvent.fire(new PostSyncedEvent(post.id));
        codexCreatorEventPublisher.postPublished(post.id, Map.of("postId", post.id,
                "revisionId", post.publishedRevision == null ? 0L : post.publishedRevision.id), null);
        return toDetail(post);
    }

    @POST
    @Path("/{id}/translation")
    @Transactional
    @Blocking
    @Operation(summary = "使用 AI 翻译文章内容")
    @APIResponse(responseCode = "200", description = "翻译成功")
    @APIResponse(responseCode = "400", description = "翻译参数或 AI 配置无效")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public PostTranslationResponse translate(@PathParam("id") Long id, PostTranslationRequest request) {
        Post post = mustFindPost(id);
        if (request == null) {
            throw badRequest("翻译请求不能为空");
        }

        String sourceLanguage = normalizeLanguage(request.sourceLanguage(), "sourceLanguage");
        String targetLanguage = normalizeLanguage(request.targetLanguage(), "targetLanguage");
        if (sourceLanguage.equals(targetLanguage)) {
            throw badRequest("源语言和目标语言不能相同");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw badRequest("文章标题不能为空");
        }
        if (request.contentMarkdown() == null || request.contentMarkdown().isBlank()) {
            throw badRequest("文章正文不能为空");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", request.title());
        fields.put("summary", request.summary() == null ? "" : request.summary());
        fields.put("contentMarkdown", request.contentMarkdown());

        try {
            com.biliwind.blog.service.ai.AiResult result = aiManager
                    .translate(sourceLanguage, targetLanguage, fields)
                    .toCompletableFuture()
                    .join();
            String translatedTitle = requiredTranslation(result, "title");
            String translatedSummary = requiredTranslation(result, "summary");
            String translatedContent = requiredTranslation(result, "contentMarkdown");
            auditService.log("post", post.id, "ai_translation", null,
                    Map.of("sourceLanguage", sourceLanguage, "targetLanguage", targetLanguage));
            postAiMetadataService.record(post.id, post.currentRevision == null ? null : post.currentRevision.id,
                    "translate", result, "DRAFT");
            return new PostTranslationResponse(sourceLanguage, targetLanguage,
                    translatedTitle, translatedSummary, translatedContent);
        } catch (java.util.concurrent.CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw badRequest("AI 翻译失败: " + safeErrorMessage(cause));
        }
    }

    @POST
    @Path("/{id}/revisions/{revisionNumber}/publish")
    @Transactional
    @Operation(summary = "发布指定文章版本")
    @APIResponse(responseCode = "200", description = "发布成功")
    @APIResponse(responseCode = "404", description = "文章或版本不存在")
    @APIResponse(responseCode = "409", description = "内容相同")
    public AdminPostDetail publishRevision(@PathParam("id") Long id, @PathParam("revisionNumber") int revisionNumber, EmailDispatchRequest emailDispatchRequest) {
        Post post = mustFindPost(id);
        boolean wasPendingReview = post.status == PostStatus.PENDING_REVIEW;
        PostRevision revision = findRevisionOrThrow(id, revisionNumber);
        if (post.status == PostStatus.PUBLISHED && sameRevision(post.publishedRevision, revision)) {
            throw conflict("内容相同");
        }
        publishRevision(post, revision, OffsetDateTime.now());
        if (wasPendingReview) {
            userNotificationService.notifyPostPublished(post);
        }
        articleEmailNotificationService.queueArticleUpdate(post, emailDispatchRequest);
        esSyncEvent.fire(new PostSyncedEvent(post.id));
        return toDetail(post);
    }

    @POST
    @Path("/{id}/reject")
    @Transactional
    @Operation(summary = "退回用户投稿")
    @APIResponse(responseCode = "200", description = "已退回")
    @APIResponse(responseCode = "409", description = "文章不在待审核状态")
    public AdminPostDetail reject(@PathParam("id") Long id, ReviewDecisionRequest request) {
        Post post = mustFindPost(id);
        if (post.status != PostStatus.PENDING_REVIEW) {
            throw conflict("只有待审核文章可以退回");
        }
        post.status = PostStatus.DRAFT;
        post.reviewedAt = OffsetDateTime.now();
        post.reviewedBy = mustFindOperator();
        post.reviewNote = request == null || request.note() == null ? null : request.note().trim();
        post.updatedAt = OffsetDateTime.now();
        userNotificationService.notifyPostRejected(post);
        auditService.log("post", post.id, "reject", null,
                Map.of("reviewNote", post.reviewNote == null ? "" : post.reviewNote));
        return toDetail(post);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "软删除文章")
    @APIResponse(responseCode = "200", description = "删除成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public Response delete(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        OffsetDateTime now = OffsetDateTime.now();
        post.deletedAt = now;
        post.updatedAt = now;
        contentAccessTicketService.revokePasswordTickets(post.id);
        contentAccessTicketService.revokeMediaDownloadTickets(null, post.id, null);
        esSyncEvent.fire(new PostSyncedEvent(post.id));
        auditService.log("post", post.id, "delete", java.util.Map.of("deleted", false), java.util.Map.of("deleted", true));
        return Response.ok(Map.of("success", true, "id", id)).build();
    }

    private Post mustFindPost(Long id) {
        Post post = postRepository.findVisiblePostById(id);
        if (post == null) {
            throw new NotFoundException("文章不存在");
        }
        return post;
    }

    private User mustFindOperator() {
        Long userId = adminRequestContext.getUserId();
        if (userId == null) {
            throw new WebApplicationException("未登录", Response.Status.UNAUTHORIZED);
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null) {
            throw new WebApplicationException("用户不存在或已禁用", Response.Status.UNAUTHORIZED);
        }
        return user;
    }

    private int nextRevisionNumber(Long postId) {
        PostRevision latest = PostRevision.find("post.id = ?1 order by revisionNumber desc", postId).firstResult();
        if (latest == null) {
            return 1;
        }
        return latest.revisionNumber + 1;
    }

    private RuntimeException conflict(String message) {
        return new com.biliwind.blog.common.exception.ConflictException(message);
    }

    private RuntimeException badRequest(String message) {
        return new com.biliwind.blog.common.exception.BadRequestException(message);
    }

    private PostRenderType resolveRenderType(Short renderTypeCode) {
        if (renderTypeCode == null) {
            return PostRenderType.MARKDOWN;
        }
        return requireRenderType(renderTypeCode);
    }

    private PostRenderType requireRenderType(Short renderTypeCode) {
        if (!PostRenderType.isSupportedCode(renderTypeCode)) {
            throw badRequest(
                    "renderType 不合法，可选值：0(markdown),1(html),2(vditor),3(v_builder),4(gutenberg),5(flutter_quill),6(flutter_markdown_plus)");
        }
        return PostRenderType.fromCode(renderTypeCode);
    }

    private List<Long> getTagIdsByPostId(Long postId) {
        List<PostTag> postTags = PostTag.find("post.id = ?1", postId).list();
        List<Long> tagIds = new ArrayList<>();
        for (PostTag pt : postTags) {
            tagIds.add(pt.tag.id);
        }
        return tagIds;
    }

    private List<Long> getCategoryIds(Post post) {
        if (post.categories != null && !post.categories.isEmpty()) {
            return post.categories.stream().map(category -> category.id).toList();
        }
        return post.category == null ? List.of() : List.of(post.category.id);
    }

    private void applyPostCategories(Post post, List<Long> requestedIds) {
        List<Long> uniqueIds = requestedIds == null ? List.of() : requestedIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<Category> categories = new ArrayList<>();
        for (Long id : uniqueIds) {
            Category category = Category.findById(id);
            if (category == null) throw badRequest("分类不存在: " + id);
            categories.add(category);
        }
        post.categories.clear();
        post.categories.addAll(categories);
        post.category = categories.isEmpty() ? null : categories.get(0);
    }

    private AdminPostItem toItem(Post post) {
        List<Long> tagIds = getTagIdsByPostId(post.id);

        return new AdminPostItem(
                post.id,
                post.slug,
                post.title,
                statusCode(post.status),
                post.visibility,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                post.aiSummaryStatus == null ? 0 : post.aiSummaryStatus,
                post.version,
                post.user == null ? null : post.user.id,
                PostAuthorHelper.displayName(post),
                post.category == null ? null : post.category.id,
                getCategoryIds(post),
                tagIds,
                post.publishedRevision == null ? 0 : post.publishedRevision.revisionNumber,
                post.publishedRevision != null,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
    }

    private AdminPostDetail toDetail(Post post) {
        List<Long> tagIds = getTagIdsByPostId(post.id);

        return new AdminPostDetail(
                post.id,
                post.slug,
                post.title,
                post.summary,
                post.aiSummary,
                post.currentRevision == null ? Map.of() : post.currentRevision.contentMarkdown,
                statusCode(post.status),
                post.visibility,
                null,
                post.password != null && !post.password.isBlank(),
                post.seoTitle,
                post.seoKeywords,
                post.seoDescription,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                post.currentRevision == null ? 0 : post.currentRevision.editorType,
                post.aiSummaryStatus == null ? 0 : post.aiSummaryStatus,
                post.currentRevision == null ? 0 : post.currentRevision.revisionNumber,
                post.version,
                postAccessService.getExtraPointsPrice(post),
                postAccessService.getFreeLines(post),
                post.user == null ? null : post.user.id,
                PostAuthorHelper.displayName(post),
                post.category == null ? null : post.category.id,
                getCategoryIds(post),
                tagIds,
                post.visibilityRegions,
                post.contentDeclarations,
                repostPolicyCatalog.resolve(post).code(),
                post.publishedRevision == null ? 0 : post.publishedRevision.revisionNumber,
                post.publishedRevision != null,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
    }



    private PostStatus resolveStatus(Short status, PostStatus fallback) {
        if (status == null) {
            return fallback;
        }
        PostStatus resolved = PostStatus.fromCode(status);
        if (resolved == null) {
            throw conflict("未知文章状态: " + status);
        }
        return resolved;
    }

    private short statusCode(PostStatus status) {
        return status == null ? PostStatus.DRAFT.getCode() : status.getCode();
    }

    private String normalizeLanguage(String language, String fieldName) {
        if (language == null || language.isBlank()) {
            throw badRequest(fieldName + " 不能为空");
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z]{2,8}(?:-[a-z0-9]{2,8})*$")) {
            throw badRequest(fieldName + " 必须是有效的语言代码");
        }
        return normalized;
    }

    private String requiredTranslation(com.biliwind.blog.service.ai.AiResult result, String field) {
        if (result == null || result.contents == null || !result.contents.containsKey(field)) {
            throw badRequest("AI 翻译结果缺少字段: " + field);
        }
        return result.contents.get(field);
    }

    private String safeErrorMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private List<String> validateContentDeclarations(List<String> requestedDeclarations) {
        if (requestedDeclarations == null || requestedDeclarations.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalizedDeclarations = new LinkedHashSet<>();
        for (String declaration : requestedDeclarations) {
            if (declaration == null || !CONTENT_DECLARATION_CODES.contains(declaration)) {
                throw badRequest("包含未知的文章内容声明");
            }
            normalizedDeclarations.add(declaration);
        }
        return new ArrayList<>(normalizedDeclarations);
    }


    private String resolveCreatedPassword(short visibility, String requestPassword) {
        if (visibility != 2) {
            return null;
        }
        if (requestPassword == null || requestPassword.isBlank()) {
            throw badRequest("密码文章必须设置访问密码");
        }
        return passwordHasher.hash(requestPassword);
    }

    private String resolveUpdatedPassword(Post post, PostUpdateRequest request) {
        short nextVisibility = post.visibility;
        if (request.visibility() != null) {
            nextVisibility = request.visibility();
        }

        if (nextVisibility != 2) {
            return null;
        }

        if (request.password() == null) {
            if (post.password == null || post.password.isBlank()) {
                throw badRequest("密码文章必须设置访问密码");
            }
            return post.password;
        }

        if (request.password().isBlank()) {
            if (post.password == null || post.password.isBlank()) {
                throw badRequest("密码文章必须设置访问密码");
            }
            return post.password;
        }

        return passwordHasher.hash(request.password());
    }


    private boolean updatePostTags(Post post, List<Long> newTagIds) {
        List<PostTag> postTags = PostTag.find("post.id = ?1", post.id).list();
        List<Long> currentTagIds = new ArrayList<>();
        for (PostTag pt : postTags) {
            currentTagIds.add(pt.tag.id);
        }

        Set<Long> currentSet = new HashSet<>(currentTagIds);
        Set<Long> newSet = new HashSet<>(newTagIds != null ? newTagIds : List.of());

        boolean changed = false;

        Set<Long> toRemove = new HashSet<>(currentSet);
        toRemove.removeAll(newSet);
        if (!toRemove.isEmpty()) {
            PostTag.delete("post.id = ?1 and tag.id in ?2", post.id, toRemove);
            changed = true;
        }

        Set<Long> toAdd = new HashSet<>(newSet);
        toAdd.removeAll(currentSet);
        for (Long tagId : toAdd) {
            Tag tag = Tag.findById(tagId);
            if (tag != null) {
                PostTag postTag = new PostTag();
                postTag.id = new PostTagId(post.id, tag.id);
                postTag.post = post;
                postTag.tag = tag;
                postTag.persist();
                changed = true;
            }
        }

        return changed;
    }

    private boolean updateBasicFields(Post post, PostUpdateRequest request) {
        boolean changed = false;

        if (request.slug() != null) {
            String nextSlug = request.slug().trim();
            if (nextSlug.isBlank()) {
                nextSlug = com.biliwind.blog.common.helper.SlugHelper.slugify(request.title() != null ? request.title() : post.title);
            }

            if (!nextSlug.equals(post.slug) && Post.count("slug = ?1", nextSlug) > 0) {
                throw conflict("slug 已存在");
            }
            if (!nextSlug.equals(post.slug)) {
                changed = true;
            }
            post.slug = nextSlug;
        }

        if (request.summary() != null && !Objects.equals(post.summary, request.summary())) {
            post.summary = request.summary();
            changed = true;
        }
        if (request.aiSummary() != null && !Objects.equals(post.aiSummary, request.aiSummary())) {
            post.aiSummary = request.aiSummary();
            changed = true;
        }
        if (request.aiSummaryStatus() != null && !Objects.equals(post.aiSummaryStatus, request.aiSummaryStatus())) {
            post.aiSummaryStatus = request.aiSummaryStatus();
            changed = true;
        }
        if (request.visibility() != null && !Objects.equals(post.visibility, request.visibility())) {
            post.visibility = request.visibility();
            changed = true;
        }
        if (request.visibilityRegions() != null && !Objects.equals(post.visibilityRegions, request.visibilityRegions())) {
            post.visibilityRegions = regionValidationService.validateAndFilterRegions(request.visibilityRegions());
            changed = true;
        }
        if (request.contentDeclarations() != null) {
            List<String> nextDeclarations = validateContentDeclarations(request.contentDeclarations());
            if (!Objects.equals(post.contentDeclarations, nextDeclarations)) {
                post.contentDeclarations = nextDeclarations;
                changed = true;
            }
        }
        if (request.repostPolicyCode() != null) {
            String nextPolicyCode = repostPolicyCatalog.require(request.repostPolicyCode()).code();
            if (!Objects.equals(post.repostPolicyCode, nextPolicyCode)) {
                post.repostPolicyCode = nextPolicyCode;
                changed = true;
            }
        }
        String nextPassword = resolveUpdatedPassword(post, request);
        if (!Objects.equals(post.password, nextPassword)) {
            post.password = nextPassword;
            contentAccessTicketService.revokePasswordTickets(post.id);
            changed = true;
        }
        if (request.seoTitle() != null && !Objects.equals(post.seoTitle, request.seoTitle())) {
            post.seoTitle = request.seoTitle();
            changed = true;
        }
        if (request.seoKeywords() != null && !Objects.equals(post.seoKeywords, request.seoKeywords())) {
            post.seoKeywords = request.seoKeywords();
            changed = true;
        }
        if (request.seoDescription() != null && !Objects.equals(post.seoDescription, request.seoDescription())) {
            post.seoDescription = request.seoDescription();
            changed = true;
        }

        if (request.renderType() != null) {
            PostRenderType nextRenderType = requireRenderType(request.renderType());
            if (!Objects.equals(post.renderType, nextRenderType)) {
                post.renderType = nextRenderType;
                changed = true;
            }
        }

        if (request.status() != null) {
            PostStatus nextStatus = PostStatus.fromCode(request.status());
            if (nextStatus == null) {
                throw conflict("未知文章状态: " + request.status());
            }
            if (!Objects.equals(post.status, nextStatus)) {
                post.status = nextStatus;
                changed = true;
                if (nextStatus != PostStatus.PUBLISHED) {
                    post.publishedRevision = null;
                }
            }
        }

        return changed;
    }

    private boolean updateCategories(Post post, PostUpdateRequest request) {
        List<Long> requestedIds = request.categoryIds();
        if (requestedIds == null && request.categoryId() != null) requestedIds = List.of(request.categoryId());
        if (requestedIds == null) return false;
        List<Long> currentIds = getCategoryIds(post);
        List<Long> nextIds = requestedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (currentIds.equals(nextIds)) return false;
        applyPostCategories(post, nextIds);
        return true;
    }

    private boolean updateContent(Post post, PostUpdateRequest request) {
        if (request.title() != null || request.contentMarkdown() != null || request.editorType() != null) {
            Map<String, String> nextTitle = request.title() == null ? post.title : request.title();
            Map<String, String> currentContent = post.currentRevision == null ? Map.of()
                    : post.currentRevision.contentMarkdown;
            Map<String, String> nextContent = request.contentMarkdown() == null ? currentContent
                    : com.biliwind.blog.common.helper.PostHelper.injectBlockIds(request.contentMarkdown());
            
            short currentEditorType = post.currentRevision == null ? 0 : post.currentRevision.editorType;
            short nextEditorType = request.editorType() == null ? currentEditorType : request.editorType();
            
            boolean revisionChanged = !Objects.equals(nextTitle, post.title)
                    || !Objects.equals(nextContent, currentContent)
                    || nextEditorType != currentEditorType;
            if (revisionChanged) {
                User operator = mustFindOperator();
                PostRevision nextRevision = new PostRevision();
                nextRevision.post = post;
                nextRevision.title = nextTitle;
                nextRevision.contentMarkdown = nextContent;
                nextRevision.editorType = nextEditorType;
                nextRevision.revisionNumber = nextRevisionNumber(post.id);
                nextRevision.createdBy = operator;
                nextRevision.createdAt = OffsetDateTime.now();
                nextRevision.persist();

                post.currentRevision = nextRevision;
                mediaService.syncPostReferences(post, nextRevision.contentMarkdown);
                articleExternalLinkService.syncMarkdownLinks(post, nextRevision.contentMarkdown);
                post.title = nextRevision.title;
                return true;
            }
        }
        return false;
    }

    @GET
    @Path("/{id}/revisions")
    @Transactional
    @Operation(summary = "获取文章版本列表")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public List<PostRevisionItem> listRevisions(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        List<PostRevision> revisions = PostRevision.list(
                "post.id = ?1",
                Sort.by("revisionNumber").descending(),
                id
        );
        List<PostRevisionItem> items = new ArrayList<>();
        for (PostRevision rev : revisions) {
            items.add(toRevisionItem(rev));
        }
        return items;
    }

    @GET
    @Path("/{id}/revisions/{revisionNumber}")
    @Transactional
    @Operation(summary = "获取文章特定版本详情")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章或版本不存在")
    public AdminPostDetail getRevision(@PathParam("id") Long id, @PathParam("revisionNumber") int revisionNumber) {
        Post post = mustFindPost(id);
        PostRevision revision = PostRevision.find(
                "post.id = ?1 and revisionNumber = ?2",
                id,
                revisionNumber
        ).firstResult();
        if (revision == null) {
            throw new NotFoundException("版本不存在");
        }

        List<Long> tagIds = getTagIdsByPostId(post.id);

        return new AdminPostDetail(
                post.id,
                post.slug,
                revision.title,
                post.summary,
                post.aiSummary,
                revision.contentMarkdown,
                statusCode(post.status),
                post.visibility,
                null,
                post.password != null && !post.password.isBlank(),
                post.seoTitle,
                post.seoKeywords,
                post.seoDescription,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                revision.editorType,
                post.aiSummaryStatus == null ? 0 : post.aiSummaryStatus,
                revision.revisionNumber,
                post.version,
                com.biliwind.blog.common.helper.PostHelper.getExtraPointsPrice(post),
                com.biliwind.blog.common.helper.PostHelper.getFreeLines(post),
                post.user == null ? null : post.user.id,
                PostAuthorHelper.displayName(post),
                post.category == null ? null : post.category.id,
                getCategoryIds(post),
                tagIds,
                post.visibilityRegions,
                post.contentDeclarations,
                repostPolicyCatalog.resolve(post).code(),
                post.publishedRevision == null ? 0 : post.publishedRevision.revisionNumber,
                post.publishedRevision != null,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
    }

    @POST
    @Path("/{id}/revisions/{revisionNumber}/activate")
    @Transactional
    @Operation(summary = "激活特定版本为当前版本")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章或版本不存在")
    public AdminPostDetail activateRevision(@PathParam("id") Long id, @PathParam("revisionNumber") int revisionNumber) {
        Post post = mustFindPost(id);
        PostRevision revision = findRevisionOrThrow(id, revisionNumber);

        User operator = mustFindOperator();
        OffsetDateTime now = OffsetDateTime.now();

        PostRevision newRevision = new PostRevision();
        newRevision.post = post;
        newRevision.title = revision.title;
        newRevision.contentMarkdown = revision.contentMarkdown;
        newRevision.editorType = revision.editorType;
        newRevision.revisionNumber = nextRevisionNumber(post.id);
        newRevision.createdBy = operator;
        newRevision.createdAt = now;
        newRevision.persist();

        post.currentRevision = newRevision;
        post.title = newRevision.title;
        post.updatedAt = now;

        mediaService.syncPostReferences(post, newRevision.contentMarkdown);
        articleExternalLinkService.syncMarkdownLinks(post, newRevision.contentMarkdown);
        esSyncEvent.fire(new PostSyncedEvent(post.id));

        return toDetail(post);
    }


    private PostRevisionItem toRevisionItem(PostRevision revision) {
        return new PostRevisionItem(
                revision.id,
                revision.revisionNumber,
                revision.title,
                revision.editorType,
                revision.createdBy == null ? null : revision.createdBy.id,
                revision.createdBy == null ? "" : revision.createdBy.username,
                isPublishedRevision(revision),
                revision.createdAt
        );
    }

    private PostRevision findRevisionOrThrow(Long postId, int revisionNumber) {
        PostRevision revision = PostRevision.find(
                "post.id = ?1 and revisionNumber = ?2",
                postId,
                revisionNumber
        ).firstResult();
        if (revision == null) {
            throw new NotFoundException("版本不存在");
        }
        return revision;
    }

    private void publishRevision(Post post, PostRevision revision, OffsetDateTime now) {
        post.publishedRevision = revision;
        post.status = PostStatus.PUBLISHED;
        if (post.publishedAt == null) {
            post.publishedAt = now;
        }
        post.updatedAt = now;
    }

    private boolean sameRevision(PostRevision leftRevision, PostRevision rightRevision) {
        if (leftRevision == null || rightRevision == null) {
            return false;
        }
        if (leftRevision.id == null || rightRevision.id == null) {
            return false;
        }
        return leftRevision.id.equals(rightRevision.id);
    }

    private Boolean isPublishedRevision(PostRevision revision) {
        if (revision == null || revision.post == null || revision.post.publishedRevision == null) {
            return false;
        }
        if (revision.id == null || revision.post.publishedRevision.id == null) {
            return false;
        }
        return revision.id.equals(revision.post.publishedRevision.id);
    }
}
