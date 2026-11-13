package com.biliwind.blog.controller;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.helper.PostCoverImageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.PostAccessPolicy;
import com.biliwind.blog.service.PageCachePolicy;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.qute.TemplateInstance;
import org.jboss.logging.Logger;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/")
@io.smallrye.common.annotation.Blocking
public class PostController {

    private static final Logger LOG = Logger.getLogger(PostController.class);

    private static final Map<String, String> CONTENT_DECLARATION_LABELS = Map.of(
            "EXPLICIT_AND_EMBEDDED_ADVERTISING", "包含显式广告和植入式广告",
            "AI_GENERATED_CONTENT", "存在 AI 生成内容",
            "SUBJECTIVE_VIEWPOINTS", "存在主观观点",
            "AUTOMATION_USE_ALLOWED", "可用于自动化程序");

    @Inject
    PageRenderer pageRenderer;

    @Inject
    LanguageContext languageContext;

    @Inject
    com.biliwind.blog.service.PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.service.PostAccessPolicy postAccessPolicy;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier userTokenVerifier;

    @Inject
    RegionContext regionContext;

    @Inject
    com.biliwind.blog.service.StructuredDataService structuredDataService;

    @Inject
    com.biliwind.blog.service.CanonicalUrlService canonicalUrlService;

    @Inject com.biliwind.blog.service.SeoAuditService seoAuditService;
    @Inject com.biliwind.blog.service.SeoPageService seoPageService;
    @Inject com.biliwind.blog.service.PageIdentityService pageIdentityService;
    @Inject com.biliwind.blog.context.PageIdentityContext pageIdentityContext;

    @Inject
    com.biliwind.blog.service.MediaAccessService mediaAccessService;

    @Inject
    com.biliwind.blog.service.repost.AffiliateContentRenderService affiliateContentRenderService;

    @Inject
    com.biliwind.blog.service.repost.RepostLicenseService repostLicenseService;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    com.biliwind.blog.service.repost.RepostReferralTrackingService repostReferralTrackingService;

    @Inject
    com.biliwind.blog.service.link.ArticleExternalLinkService articleExternalLinkService;

    @Inject
    com.biliwind.blog.service.PublicContentSanitizer publicContentSanitizer;

    @Inject
    com.biliwind.blog.service.PublicCacheRefreshService publicCacheRefreshService;

    @GET
    @Path("/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public Response post(@PathParam("slug") String slug,
                         @QueryParam("levels") List<Short> levels,
                         @QueryParam("wb_repost") String repostCode,
                         @Context HttpHeaders httpHeaders,
                         @Context Request request) {
        return render(slug, null, levels, repostCode, httpHeaders, request);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public Response postWithLang(@PathParam("langCode") String langCode,
                                 @PathParam("slug") String slug,
                                 @QueryParam("levels") List<Short> levels,
                                 @QueryParam("wb_repost") String repostCode,
                                 @Context HttpHeaders httpHeaders,
                                 @Context Request request) {
        return render(slug, langCode, levels, repostCode, httpHeaders, request);
    }

    private Response render(String slug,
                            String langCode,
                            List<Short> levels,
                            String repostCode,
                            HttpHeaders httpHeaders,
                            Request request) {

        resolveLanguage(langCode);

        slug = normalizeSlug(slug);

        String currentRegion = regionContext.getCurrentRegion().getCode();
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        boolean trackedReferral = repostCode != null && !repostCode.isBlank();

        Optional<com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot> snapshotResult =
                publicCacheRefreshService.findPublishedSnapshot(slug, currentRegion);
        if (snapshotResult.isEmpty()) {
            throw new NotFoundException("Post not found: " + slug);
        }
        com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot =
                snapshotResult.get();

        if (trackedReferral) {
            try {
                repostReferralTrackingService.record(repostCode, httpHeaders);
            } catch (Exception exception) {
                LOG.debugf(exception, "Failed to record repost referral for code %s", repostCode);
            }
        }

        String resolvedLang = languageContext.getLang();
        Long requestUserId = resolveUserId(httpHeaders);
        String deviceId = httpHeaders.getHeaderString("X-Device-Id");

        java.time.OffsetDateTime lastUpdated = snapshot.updatedAt();
        if (lastUpdated == null) {
            lastUpdated = snapshot.publishedAt();
        }
        if (lastUpdated == null) {
            lastUpdated = java.time.OffsetDateTime.now();
        }
        long epoch = lastUpdated.toEpochSecond();

        com.biliwind.blog.model.SeoPostPolicy seoPolicy = seoPageService.policy(snapshot.postId());
        com.biliwind.blog.service.PageIdentityService.ArticleIdentity article =
                pageIdentityService.article(snapshot, snapshot.previewContent(), resolvedLang,
                        snapshot.visibility() != 0, seoPolicy);
        com.biliwind.blog.service.PageIdentity identity = article.identity();
        pageIdentityContext.set(identity);
        String contentLanguage = article.contentLanguage();
        languageContext.setContentLanguage(contentLanguage);
        String localizedTitle = article.displayTitle();
        String publicPreview = resolveSnapshotContent(snapshot, resolvedLang);
        String localizedContent = resolveSnapshotContent(snapshot, resolvedLang);
        boolean localizedContentRendered = true;
        Post postEntity = Post.find(
                "id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                snapshot.postId(), PostStatus.PUBLISHED).firstResult();
        boolean protectedPage = snapshot.visibility() == 2;
        boolean contentAccessible = snapshot.visibility() == 0;
        boolean hasPurchased = false;
        if (protectedPage) {
            PostAccessPolicy.Decision access = postAccessPolicy.evaluate(
                    postEntity, requestUserId, null,
                    readPasswordTicket(httpHeaders, snapshot.postId()), deviceId);
            if (access.allowed() && postEntity != null) {
                contentAccessible = true;
                localizedContent = resolveContent(postEntity.publishedRevision, resolvedLang);
                localizedContentRendered = false;
                hasPurchased = postAccessService.hasPurchasedPost(requestUserId, postEntity.id);
                localizedContent = postAccessService.rewriteProtectedMediaReferences(
                        postEntity, localizedContent, requestUserId, hasPurchased, deviceId);
            }
        }
        if (postEntity != null) {
            boolean author = postAccessPolicy.isAuthor(postEntity, requestUserId);
            hasPurchased = author || postAccessService.hasPurchasedPost(requestUserId, postEntity.id);
        }

        long postPrice = snapshot.postPrice();
        String ampUrl = identity.ampUrl();

        com.biliwind.blog.model.translation.ParagraphTranslationMeta paragraphMeta = null;
        if (postEntity != null && postEntity.publishedRevision != null
                && postEntity.publishedRevision.paragraphTranslations != null) {
            paragraphMeta = postEntity.publishedRevision.paragraphTranslations.get(resolvedLang);
            if (paragraphMeta == null) {
                for (var e : postEntity.publishedRevision.paragraphTranslations.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(resolvedLang)) {
                        paragraphMeta = e.getValue();
                        break;
                    }
                }
            }
        }
        boolean isTranslationOutdated = paragraphMeta != null && paragraphMeta.outdated();

        final com.biliwind.blog.model.translation.ParagraphTranslationMeta translationMeta = paragraphMeta;
        final String renderedContent = localizedContent;
        final boolean renderedContentIsPreview = localizedContentRendered;
        // Rendering the body mints affiliate/link tokens, so it must never feed
        // the validator. It stays lazy and runs only when the page is rendered.
        java.util.concurrent.atomic.AtomicReference<PostBodyView> renderedBodyCache =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Supplier<PostBodyView> renderedBody = () -> {
            PostBodyView cached = renderedBodyCache.get();
            if (cached != null) {
                return cached;
            }
            PostBodyView body = renderedContentIsPreview
                    ? resolveSnapshotPreviewBody(snapshot.renderType(), renderedContent)
                    : resolvePostBody(snapshot.renderType(), renderedContent, translationMeta);
            if (body.html()) {
                // Affiliate token issuance is intentionally kept off the public read model;
                // the source entity is loaded only when active commercial links exist.
                String rewrittenPostBody = affiliateContentRenderService.rewriteCommercialLinks(
                        snapshot.postId(), body.body());
                rewrittenPostBody = articleExternalLinkService.rewriteArticleExternalLinks(rewrittenPostBody);
                body = new PostBodyView(rewrittenPostBody, true, body.renderType());
            }
            renderedBodyCache.set(body);
            return body;
        };
        java.util.function.Supplier<String> publicHtml = () -> snapshot.postPrice() > 0 || protectedPage
                ? resolveSnapshotPreviewBody(snapshot.renderType(), publicPreview).body()
                : renderedBody.get().body();

        String localizedAiSummary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), resolvedLang);

        List<TagItem> postTags = new java.util.ArrayList<>();
        if (snapshot.tags() != null) {
            for (com.biliwind.blog.service.PublicCacheRefreshService.PublicTagSnapshot tag : snapshot.tags()) {
                postTags.add(new TagItem(LanguageHelper.resolveLocalizedValue(tag.name(), resolvedLang), tag.slug()));
            }
        }

        final boolean accessibleContent = contentAccessible;
        final boolean purchased = hasPurchased;
        java.util.function.Supplier<List<AttachmentView>> attachments = () -> {
            List<AttachmentView> items = new java.util.ArrayList<>();
            if (accessibleContent && postEntity != null) {
                java.util.Set<String> attachmentNames = new java.util.HashSet<>();
                String attachmentContent = resolveContent(postEntity.publishedRevision, resolvedLang);
                for (com.biliwind.blog.service.PostAccessService.AttachmentItem item
                        : postAccessService.resolveAttachmentItems(postEntity, attachmentContent,
                                requestUserId, deviceId)) {
                    items.add(new AttachmentView(item.blockId(), item.name(), item.formattedSize(),
                            item.price(), item.unlocked(), item.loginRequired(), item.downloadUrl()));
                    attachmentNames.add(item.name());
                }
                if (snapshot.attachments() != null && !snapshot.attachments().isEmpty()) {
                    List<PostMedia> legacyAttachments = PostMedia.find(
                            "select pm from PostMedia pm join fetch pm.media "
                                    + "where pm.post.id = ?1 and pm.usageType = 3",
                            postEntity.id).list();
                    for (PostMedia legacy : legacyAttachments) {
                        if (legacy.media == null || legacy.media.deletedAt != null) {
                            continue;
                        }
                        if (!mediaAccessService.canAccess(legacy.media, regionContext.getCurrentRegion())) {
                            continue;
                        }
                        String fileName = legacy.media.fileName;
                        if (fileName == null || fileName.isBlank() || !attachmentNames.add(fileName)) {
                            continue;
                        }
                        String formattedSize = formatAttachmentSize(legacy.media.size);
                        boolean unlocked = purchased;
                        String downloadUrl = unlocked
                                ? postAccessService.issueAttachmentDownloadUrl(legacy.media.id,
                                        postEntity.id, requestUserId, deviceId)
                                : null;
                        items.add(new AttachmentView(null, fileName, formattedSize, 0L,
                                unlocked, false, downloadUrl));
                    }
                }
            }
            return items;
        };

        String displayTitle = localizedTitle;
        String seoTitle = article.seoTitle();
        String pageDescription = article.description();
        String pageKeywords = article.keywords();
        String canonicalUrl = identity.canonicalUrl();

        int aiSummaryStatusVal = 0;
        if (snapshot.aiSummaryStatus() != null) {
            aiSummaryStatusVal = snapshot.aiSummaryStatus().intValue();
        }
        String authorName = snapshot.authorName();
        if (authorName == null || authorName.isBlank()) {
            authorName = "Unknown";
        }

        String authorAvatar = "/assets/img/avatar-default.svg";

        PostRenderType effectiveRenderType = snapshot.renderType() == null
                ? PostRenderType.MARKDOWN : snapshot.renderType();
        String renderTypeName = effectiveRenderType.name();

        String postCategory = LanguageHelper.resolveLocalizedValue(snapshot.categoryName(), resolvedLang);
        if (postCategory == null || postCategory.isBlank()) {
            postCategory = "未分类";
        }
        String postCategorySlug = snapshot.categorySlug();
        if (postCategorySlug == null || postCategorySlug.isBlank()) {
            postCategorySlug = "uncategorized";
        }
        List<PostCategoryItem> postCategories = snapshot.categories() == null ? List.of()
                : snapshot.categories().stream().map(item -> new PostCategoryItem(
                        LanguageHelper.resolveLocalizedValue(item.name(), resolvedLang), item.slug())).toList();
        if (postCategories.isEmpty() && snapshot.categorySlug() != null) {
            postCategories = List.of(new PostCategoryItem(postCategory, postCategorySlug));
        }

        RepostPolicyCatalog.Policy repostPolicy = repostPolicyCatalog.resolve(
                snapshot.repostPolicyCode(), snapshot.contentDeclarations());
        String repostOriginalUrl = repostLicenseService.buildPostUrl(slug);
        String repostDirectCopyText = repostPolicy.requiresApplication()
                ? ""
                : repostPolicyCatalog.buildDirectCopyText(repostPolicy, displayTitle, repostOriginalUrl, resolvedLang);

        PostCoverImageHelper.CoverImageInfo featuredInfo = postEntity == null ? null
                : com.biliwind.blog.common.helper.PostCoverImageHelper
                        .findFeaturedImageInfos(List.of(postEntity.id))
                        .get(postEntity.id);
        PostCoverImageHelper.CoverImageInfo coverInfo = postEntity != null
                ? com.biliwind.blog.common.helper.PostCoverImageHelper
                        .resolveCoverImageInfo(postEntity, resolvedLang, featuredInfo)
                : new PostCoverImageHelper.CoverImageInfo(
                        com.biliwind.blog.common.helper.PostCoverImageHelper.firstImageUrl(snapshot.previewContent(), resolvedLang),
                        null);
        String postCoverImageUrl = coverInfo.url();
        String postCoverThumbhash = coverInfo.thumbhash();

        com.biliwind.blog.model.ArticleMode articleMode = snapshot.articleMode() == null
                ? com.biliwind.blog.model.ArticleMode.STANDARD
                : snapshot.articleMode();
        String articleModeName = articleMode.value();

        com.biliwind.blog.model.TutorialMeta tutorialMeta = snapshot.tutorialMeta();
        String tutorialDifficulty = "";
        Integer tutorialEstimatedMinutes = null;
        List<String> tutorialPrerequisites = List.of();
        String tutorialTargetAudience = "";
        if (tutorialMeta != null) {
            tutorialDifficulty = LanguageHelper.resolveLocalizedValue(tutorialMeta.difficulty(), resolvedLang);
            tutorialEstimatedMinutes = tutorialMeta.estimatedMinutes();
            if (tutorialMeta.prerequisites() != null) {
                tutorialPrerequisites = tutorialMeta.prerequisites().getOrDefault(resolvedLang,
                        tutorialMeta.prerequisites().values().stream().findFirst().orElse(List.of()));
            }
            tutorialTargetAudience = LanguageHelper.resolveLocalizedValue(tutorialMeta.targetAudience(), resolvedLang);
        }

        com.biliwind.blog.model.ResourceMeta resourceMeta = snapshot.resourceMeta();
        String resourceVersion = "";
        String resourceFileSize = "";
        List<String> resourcePlatforms = List.of();
        String resourceOfficialUrl = "";
        String resourceLicense = "";
        String resourceChecksumSha256 = "";
        String resourceChecksumMd5 = "";
        List<com.biliwind.blog.model.ResourceMirror> resourceMirrors = List.of();
        if (resourceMeta != null) {
            resourceVersion = resourceMeta.version() == null ? "" : resourceMeta.version();
            resourceFileSize = resourceMeta.fileSize() == null ? "" : resourceMeta.fileSize();
            resourcePlatforms = resourceMeta.platforms() == null ? List.of() : resourceMeta.platforms();
            resourceOfficialUrl = resourceMeta.officialUrl() == null ? "" : resourceMeta.officialUrl();
            resourceLicense = resourceMeta.license() == null ? "" : resourceMeta.license();
            resourceChecksumSha256 = resourceMeta.checksumSha256() == null ? "" : resourceMeta.checksumSha256();
            resourceChecksumMd5 = resourceMeta.checksumMd5() == null ? "" : resourceMeta.checksumMd5();
            resourceMirrors = resourceMeta.mirrors() == null ? List.of() : resourceMeta.mirrors();
        }

        PageRenderModel model = new PageRenderModel()
                .put("language", resolvedLang)
                .put("pjaxRequest", pjaxRequest)
                .put("contentLanguage", contentLanguage)
                .put("postSlug", slug)
                .put("articleMode", articleModeName)
                .put("tutorialDifficulty", tutorialDifficulty)
                .put("tutorialEstimatedMinutes", tutorialEstimatedMinutes)
                .put("tutorialPrerequisites", tutorialPrerequisites)
                .put("tutorialTargetAudience", tutorialTargetAudience)
                .put("resourceVersion", resourceVersion)
                .put("resourceFileSize", resourceFileSize)
                .put("resourcePlatforms", resourcePlatforms)
                .put("resourceOfficialUrl", resourceOfficialUrl)
                .put("resourceLicense", resourceLicense)
                .put("resourceChecksumSha256", resourceChecksumSha256)
                .put("resourceChecksumMd5", resourceChecksumMd5)
                .put("resourceMirrors", resourceMirrors)
                .put("postCoverImageUrl", postCoverImageUrl)
                .put("postCoverThumbhash", postCoverThumbhash)
                .derived("wordCount", () -> com.biliwind.blog.common.helper.PostHelper
                        .calculateWordCount(renderedBody.get().body()))
                .derived("readingTimeMinutes", () -> com.biliwind.blog.common.helper.PostHelper
                        .calculateReadingTimeMinutes(com.biliwind.blog.common.helper.PostHelper
                                .calculateWordCount(renderedBody.get().body())))
                .put("internalLinkGraphVisible", snapshot.visibility() == 0 && com.biliwind.blog.service.ConfigTemplateData.internalLinkGraphEnabled())
                .put("isTranslationOutdated", isTranslationOutdated)
                .put("postTitle", displayTitle)
                .put("pageTitle", seoTitle)
                .put("pageDescription", pageDescription)
                .put("pageKeywords", pageKeywords)
                .put("aiSummary", localizedAiSummary)
                .put("aiSummaryStatus", aiSummaryStatusVal)
                .derived("postBody", () -> renderedBody.get().body())
                .derived("postBodyHtml", () -> renderedBody.get().html())
                .put("publishedAt", snapshot.publishedAt())
                .put("authorName", authorName)
                .put("authorAvatar", authorAvatar)
                .put("postRenderType", renderTypeName)
                .derived("postBodyJson", () -> escapeJavaScript(renderedBody.get().body()))
                .put("postCategory", postCategory)
                .put("postCategorySlug", postCategorySlug)
                .put("breadcrumbCategorySlug", snapshot.categorySlug())
                .put("postCategories", postCategories)
                .put("postPrice", postPrice)
                // Public pages use the read model; protected pages are no-store and may
                // render authorized content loaded from the published revision.
                .put("hasPurchased", hasPurchased)
                .put("postTags", postTags)
                .put("contentDeclarations", resolveContentDeclarationLabels(snapshot.contentDeclarations()))
                .derived("attachments", attachments)
                .put("userAuthenticated", requestUserId != null)
                .put("attachmentLoginUrl", buildAttachmentLoginUrl(slug, resolvedLang))
                .put("relatedStoreItems", snapshot.relatedStoreItems())
                .put("relatedArticles", seoAuditService.related(postEntity, resolvedLang))
                .put("repostPolicyCode", repostPolicy.code())
                .put("repostPolicyName", repostPolicy.nameFor(resolvedLang))
                .put("repostPolicySummary", repostPolicy.summaryFor(resolvedLang))
                .put("repostPolicyRequiresApplication", repostPolicy.requiresApplication())
                .put("repostPolicyConditions", repostPolicy.conditionsFor(resolvedLang))
                .put("repostPolicyLicenseUrl", repostPolicy.licenseUrl())
                .put("repostDirectCopyText", repostDirectCopyText)
                .put("repostOriginalUrl", repostOriginalUrl)
                .put("canonicalUrl", canonicalUrl)
                .put("ampUrl", ampUrl)
                .derived("ogData", () -> buildOgData(canonicalUrl, seoTitle, pageDescription,
                        publicHtml.get(), snapshot, contentLanguage))
                .derived("jsonLd", () -> structuredDataService.article(snapshot, seoTitle, pageDescription,
                        canonicalUrl, contentLanguage, publicHtml.get()))
                .put("pageIdentity", identity)
                .put("siteConfig", pageIdentityService.environmentFingerprint())
                .put("requestUser", requestUserId)
                .put("region", currentRegion)
                .put("updatedEpoch", epoch)
                .put("content", localizedContent)
                .put("publicPreview", publicPreview)
                .put("translationMeta", translationMeta)
                .put("contentAccessible", contentAccessible)
                .put("postEntityId", postEntity == null ? null : postEntity.id)
                .put("deviceId", deviceId)
                .put("trackedReferral", trackedReferral);

        EntityTag etag = model.validator();

        // 评估客户端提供的 If-None-Match 首部（仅限未登录访客、非受保护且非推广的公开文章）
        if (!protectedPage && !trackedReferral && requestUserId == null) {
            Response.ResponseBuilder precondition = request.evaluatePreconditions(etag);
            if (precondition != null) {
                return PageCachePolicy.publicDocument(precondition, etag).build();
            }
        }

        TemplateInstance templateInstance = pageRenderer.render(PageView.POST, httpHeaders, model);
        Response.ResponseBuilder responseBuilder = Response.ok(templateInstance);
        boolean isPrivateContext = protectedPage || trackedReferral || requestUserId != null || hasPurchased;
        if (isPrivateContext) {
            responseBuilder = PageCachePolicy.privateNoStore(responseBuilder);
        } else {
            responseBuilder = PageCachePolicy.publicDocument(responseBuilder, etag);
        }
        return responseBuilder.build();
    }

    @RegisterForReflection
    public record PostCategoryItem(String name, String slug) {}

    private List<String> resolveContentDeclarationLabels(List<String> declarationCodes) {
        List<String> labels = new java.util.ArrayList<>();
        if (declarationCodes == null) {
            return labels;
        }
        for (String declarationCode : declarationCodes) {
            String label = CONTENT_DECLARATION_LABELS.get(declarationCode);
            if (label != null) {
                labels.add(label);
            }
        }
        return labels;
    }

    private java.util.Map<String, String> buildOgData(String canonicalUrl, String title, String summary,
                                                      String publicHtml,
                                                      com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
                                                      String language) {
        java.util.Map<String, String> og = new java.util.HashMap<>();
        og.put("og:title", title);
        og.put("og:description", summary != null ? summary : "");
        og.put("og:type", "article");
        og.put("og:url", canonicalUrl);
        og.put("og:site_name", com.biliwind.blog.service.ConfigTemplateData.siteTitle());
        og.put("og:locale", language != null
                && language.toLowerCase(java.util.Locale.ROOT).startsWith("en") ? "en_US" : "zh_CN");
        if (snapshot.publishedAt() != null) {
            og.put("article:published_time", snapshot.publishedAt().toString());
        }
        if (snapshot.updatedAt() != null) {
            og.put("article:modified_time", snapshot.updatedAt().toString());
        }

        List<String> images = com.biliwind.blog.service.StructuredDataService.imageUrls(publicHtml, canonicalUrl);
        String imageUrl = images.isEmpty()
                ? structuredDataService.publisherLogoUrl()
                : images.getFirst();
        if (imageUrl != null) og.put("og:image", imageUrl);

        og.put("twitter:card", "summary_large_image");
        og.put("twitter:title", title);
        og.put("twitter:description", summary != null ? summary : "");
        if (imageUrl != null) og.put("twitter:image", imageUrl);

        return og;
    }

    @RegisterForReflection
    public record TagItem(String name, String slug) {}

    private String escapeJavaScript(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("'", "\\'");
    }

    private void resolveLanguage(String langCode) {

        if (langCode == null || langCode.isBlank()) {
            // No legacy route parameter: keep the language chosen by URL prefix,
            // ?lang, cookie or the region default. The page language no longer
            // depends on which article translations happen to exist.
            return;
        }

        String normalized =
                LanguageHelper.normalizeToSupportedLang(langCode);

        languageContext.setLang(
                normalized == null
                        ? LanguageConstant.DEFAULT_LANG
                        : normalized
        );
    }

    private String normalizeSlug(String slug) {

        if (slug != null && slug.toLowerCase().endsWith(".html")) {
            slug = slug.substring(0, slug.length() - 5);
        }

        if (slug == null || slug.isBlank()) {
            return "untitled";
        }

        return slug;
    }

    private String resolveContent(PostRevision revision,
                                  String lang) {

        if (revision == null) {
            return null;
        }

        return LanguageHelper
                .resolveLocalizedValue(revision.contentMarkdown, lang);
    }

    private PostBodyView resolvePostBody(PostRenderType renderType,
                                         String content,
                                         com.biliwind.blog.model.translation.ParagraphTranslationMeta meta) {

        if (content == null || content.isBlank()) {
            return new PostBodyView("", false, null);
        }

        PostRenderType effective =
                renderType == null
                        ? PostRenderType.MARKDOWN
                        : renderType;

        return switch (effective) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS ->
                    new PostBodyView(com.biliwind.blog.common.helper.MarkdownParagraphHelper.renderHtmlWithOutdatedMarkers(content, meta, null), true, effective);
            case VDITOR ->
                    new PostBodyView(publicContentSanitizer.sanitize(content), true, effective);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL, TUTORIAL_BLOCK ->
                    new PostBodyView(publicContentSanitizer.sanitize(content), true, effective);
        };
    }

    /**
     * Snapshot preview content is already sanitized HTML with server-owned
     * purchase controls. It is served as-is: sanitizing again would delete
     * those controls, and the per-paragraph outdated-translation renderer
     * ignores the truncated preview entirely (it would also bypass the
     * paywall). Raw, non-preview bodies keep their outdated markers.
     */
    private PostBodyView resolveSnapshotPreviewBody(PostRenderType renderType, String content) {
        PostRenderType effective = renderType == null ? PostRenderType.MARKDOWN : renderType;
        return new PostBodyView(content == null ? "" : content, true, effective);
    }

    private String resolveSnapshotContent(
            com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
            String language) {
        if (snapshot.previewContent() == null) {
            return "";
        }
        String content = snapshot.previewContent().get(language);
        if (content != null) {
            return content;
        }
        return LanguageHelper.resolveLocalizedValue(snapshot.previewContent(), language);
    }

    private String readPasswordTicket(HttpHeaders headers, Long postId) {
        if (headers == null || postId == null) {
            return null;
        }
        Cookie cookie = headers.getCookies().get("post_access_ticket_" + postId);
        return cookie == null ? null : cookie.getValue();
    }

    private Long resolveUserId(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        com.biliwind.blog.common.security.UserTokenVerifier.VerifiedToken verified =
                userTokenVerifier.verify(cookie.getValue());
        return verified == null ? null : verified.uid();
    }

    private record PostBodyView(String body, boolean html, PostRenderType renderType) {}

    @RegisterForReflection
    public record AttachmentView(String blockId, String fileName, String formattedSize, long price,
                                 boolean unlocked, boolean loginRequired, String downloadUrl) {
    }

    private String buildAttachmentLoginUrl(String slug, String language) {
        String postPath = LanguageHelper.localizedPath("/post/" + slug, language,
                canonicalUrlService.getDefaultLanguage());
        String loginPath = LanguageHelper.localizedPath("/user/login", language,
                canonicalUrlService.getDefaultLanguage());
        return loginPath + "?redirect="
                + java.net.URLEncoder.encode(postPath, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String formatAttachmentSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

}
