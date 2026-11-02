package com.biliwind.blog.controller;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.helper.SearchContentHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.PostAccessPolicy;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
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
    @Location("blog/post.html")
    Template postTemplate;

    @Inject
    @Location("blog/post.content.html")
    Template postContentTemplate;

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
    com.biliwind.blog.service.ConfigManager configManager;

    @Inject
    com.biliwind.blog.service.PublicUrlService publicUrlService;

    @Inject
    com.biliwind.blog.service.CanonicalUrlService canonicalUrlService;

    @Inject com.biliwind.blog.service.SeoAuditService seoAuditService;
    @Inject com.biliwind.blog.service.SeoManagementService seoManagementService;

    @Inject
    com.biliwind.blog.service.AmpPageService ampPageService;

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

        // 计算 ETag 逻辑。使用显式 if/else，禁止三目
        java.time.OffsetDateTime lastUpdated = snapshot.updatedAt();
        if (lastUpdated == null) {
            lastUpdated = snapshot.publishedAt();
        }
        if (lastUpdated == null) {
            lastUpdated = java.time.OffsetDateTime.now();
        }

        long epoch = lastUpdated.toEpochSecond();
        String etagValue = snapshot.postId() + "_" + epoch + "_" + resolvedLang + "_" + currentRegion
                + (pjaxRequest ? "_pjax" : "_full")
                // The attachment widget and content gate depend on the reader,
                // so a cached anonymous body must not satisfy a logged-in request.
                + (requestUserId == null ? "_anon" : "_u" + requestUserId)
                // RESTEasy splits If-None-Match on commas, so the robots value
                // (index,follow) must not carry one into the ETag.
                + "_" + seoManagementService.robots(
                        ("zh-cn".equalsIgnoreCase(resolvedLang) ? "" : "/" + resolvedLang) + "/post/" + slug)
                        .replace(',', '_');
        // The CSP nonce changes on every render, so the validator is weak; a
        // strong tag would also fail when a CDN normalizes it to W/"...".
        EntityTag etag = new EntityTag(etagValue, true);
        boolean protectedPage = snapshot.visibility() == 2;

        // 评估客户端提供的 If-None-Match 首部
        if (!protectedPage && !trackedReferral) {
            Response.ResponseBuilder responseBuilder = request.evaluatePreconditions(etag);
            if (responseBuilder != null) {
                CacheControl cacheControl = new CacheControl();
                cacheControl.setMaxAge(60); // 缓存 60 秒
                return responseBuilder.cacheControl(cacheControl)
                        .header("Vary", "Accept-Language, Cookie")
                        .build();
            }
        }

        String contentLanguage = LanguageHelper.resolveLocalizedLanguage(snapshot.previewContent(), resolvedLang);
        languageContext.setContentLanguage(contentLanguage);
        String localizedTitle = LanguageHelper.resolveLocalizedValue(snapshot.title(), resolvedLang);
        String publicPreview = resolveSnapshotContent(snapshot, resolvedLang);
        String localizedContent = resolveSnapshotContent(snapshot, resolvedLang);
        Post postEntity = Post.find(
                "id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                snapshot.postId(), PostStatus.PUBLISHED).firstResult();
        boolean contentAccessible = snapshot.visibility() == 0;
        boolean hasPurchased = false;
        if (protectedPage) {
            PostAccessPolicy.Decision access = postAccessPolicy.evaluate(
                    postEntity, requestUserId, null,
                    readPasswordTicket(httpHeaders, snapshot.postId()), deviceId);
            if (access.allowed() && postEntity != null) {
                contentAccessible = true;
                localizedContent = resolveContent(postEntity.publishedRevision, resolvedLang);
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
        String ampUrl = null;
        if (ampPageService.isEnabled() && snapshot.visibility() == 0 && postPrice <= 0) {
            ampUrl = buildAmpUrl(slug, contentLanguage);
        }
        PostBodyView postBody = resolvePostBody(snapshot.renderType(), localizedContent);
        if (postBody.html()) {
            // Affiliate token issuance is intentionally kept off the public read model;
            // the source entity is loaded only when active commercial links exist.
            String rewrittenPostBody = affiliateContentRenderService.rewriteCommercialLinks(
                    snapshot.postId(), postBody.body());
            rewrittenPostBody = articleExternalLinkService.rewriteArticleExternalLinks(rewrittenPostBody);
            postBody = new PostBodyView(rewrittenPostBody, true, postBody.renderType());
        }

        String localizedAiSummary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), resolvedLang);

        Template template = null;
        if (pjaxRequest) {
            template = postContentTemplate;
        } else {
            template = postTemplate;
        }

        List<TagItem> postTags = new java.util.ArrayList<>();
        if (snapshot.tags() != null) {
            for (com.biliwind.blog.service.PublicCacheRefreshService.PublicTagSnapshot tag : snapshot.tags()) {
                postTags.add(new TagItem(LanguageHelper.resolveLocalizedValue(tag.name(), resolvedLang), tag.slug()));
            }
        }

        List<AttachmentView> attachments = new java.util.ArrayList<>();
        if (contentAccessible && postEntity != null) {
            java.util.Set<String> attachmentNames = new java.util.HashSet<>();
            String attachmentContent = resolveContent(postEntity.publishedRevision, resolvedLang);
            for (com.biliwind.blog.service.PostAccessService.AttachmentItem item
                    : postAccessService.resolveAttachmentItems(postEntity, attachmentContent,
                            requestUserId, deviceId)) {
                attachments.add(new AttachmentView(item.blockId(), item.name(), item.formattedSize(),
                        item.price(), item.unlocked(), item.loginRequired(), item.downloadUrl()));
                attachmentNames.add(item.name());
            }
            if (snapshot.attachments() != null && !snapshot.attachments().isEmpty()) {
                List<PostMedia> legacyAttachments =
                        PostMedia.list("post.id = ?1 and usageType = 3", postEntity.id);
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
                    boolean unlocked = hasPurchased;
                    String downloadUrl = unlocked
                            ? postAccessService.issueAttachmentDownloadUrl(legacy.media.id,
                                    postEntity.id, requestUserId, deviceId)
                            : null;
                    attachments.add(new AttachmentView(null, fileName, formattedSize, 0L,
                            unlocked, false, downloadUrl));
                }
            }
        }

        String displayTitle = localizedTitle;
        if (displayTitle == null) {
            displayTitle = slug;
        }
        String publicHtml = snapshot.postPrice() > 0 || protectedPage
                ? resolvePostBody(snapshot.renderType(), publicPreview).body() : postBody.body();
        String seoTitle = resolveSeoTitle(snapshot, displayTitle, contentLanguage);
        String pageDescription = resolvePageDescription(
                snapshot, localizedAiSummary, localizedContent, contentLanguage);
        String pageKeywords = resolveSeoKeywords(snapshot, contentLanguage);
        // Canonicalise a fallback render to the language that actually has the
        // article body; the fallback URL itself must not compete as a duplicate.
        String canonicalUrl = buildCanonicalUrl(slug, contentLanguage);

        int aiSummaryStatusVal = 0;
        if (snapshot.aiSummaryStatus() != null) {
            aiSummaryStatusVal = snapshot.aiSummaryStatus().intValue();
        }
        String authorName = snapshot.authorName();
        if (authorName == null || authorName.isBlank()) {
            authorName = "Unknown";
        }

        String authorAvatar = "/assets/img/avatar-default.svg";

        String renderTypeName = null;
        if (postBody.renderType() != null) {
            renderTypeName = postBody.renderType().name();
        }

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

        TemplateInstance templateInstance = template
                .data("language", resolvedLang)
                .data("pjaxRequest", pjaxRequest)
                .data("contentLanguage", contentLanguage)
                .data("postSlug", slug)
                .data("internalLinkGraphVisible", snapshot.visibility() == 0 && com.biliwind.blog.service.ConfigTemplateData.internalLinkGraphEnabled())
                .data("postTitle", displayTitle)
                .data("pageTitle", seoTitle)
                .data("pageDescription", pageDescription)
                .data("pageKeywords", pageKeywords)
                .data("aiSummary", localizedAiSummary)
                .data("aiSummaryStatus", aiSummaryStatusVal)
                .data("postBody", postBody.body())
                .data("postBodyHtml", postBody.html())
                .data("publishedAt", snapshot.publishedAt())
                .data("authorName", authorName)
                .data("authorAvatar", authorAvatar)
                .data("postRenderType", renderTypeName)
                .data("postBodyJson", escapeJavaScript(postBody.body()))
                .data("postCategory", postCategory)
                .data("postCategorySlug", postCategorySlug)
                .data("breadcrumbCategorySlug", snapshot.categorySlug())
                .data("postCategories", postCategories)
                .data("postPrice", postPrice)
                // Public pages use the read model; protected pages are no-store and may
                // render authorized content loaded from the published revision.
                .data("hasPurchased", hasPurchased)
                .data("postTags", postTags)
                .data("contentDeclarations", resolveContentDeclarationLabels(snapshot.contentDeclarations()))
                .data("attachments", attachments)
                .data("userAuthenticated", requestUserId != null)
                .data("attachmentLoginUrl", buildAttachmentLoginUrl(slug, resolvedLang))
                .data("relatedStoreItems", snapshot.relatedStoreItems())
                .data("relatedArticles", seoAuditService.related(postEntity, resolvedLang))
                .data("repostPolicyCode", repostPolicy.code())
                .data("repostPolicyName", repostPolicy.nameFor(resolvedLang))
                .data("repostPolicySummary", repostPolicy.summaryFor(resolvedLang))
                .data("repostPolicyRequiresApplication", repostPolicy.requiresApplication())
                .data("repostPolicyConditions", repostPolicy.conditionsFor(resolvedLang))
                .data("repostPolicyLicenseUrl", repostPolicy.licenseUrl())
                .data("repostDirectCopyText", repostDirectCopyText)
                .data("repostOriginalUrl", repostOriginalUrl)
                .data("canonicalUrl", canonicalUrl)
                .data("ampUrl", ampUrl)
                .data("ogData", buildOgData(canonicalUrl, seoTitle, pageDescription, publicHtml, snapshot, contentLanguage))
                .data("jsonLd", structuredDataService.article(snapshot, seoTitle, pageDescription,
                        canonicalUrl, contentLanguage, publicHtml));

        Response.ResponseBuilder responseBuilder = Response.ok(templateInstance)
                .header("Vary", "Accept-Language, Cookie");
        if (protectedPage || trackedReferral) {
            responseBuilder.header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0");
        } else {
            CacheControl cacheControl = new CacheControl();
            cacheControl.setMaxAge(60); // 缓存 60 秒
            responseBuilder.tag(etag).cacheControl(cacheControl);
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

    private String buildCanonicalUrl(String slug, String language) {
        return canonicalUrlService.buildLocalizedPath("/post/" + slug, language);
    }

    private String buildAmpUrl(String slug, String language) {
        String path = LanguageHelper.localizedPath("/amp/post/" + slug, language,
                canonicalUrlService.getDefaultLanguage());
        return publicUrlService.buildPath(path);
    }

    private String resolveSeoTitle(
            com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
            String displayTitle, String language) {
        String seoTitle = LanguageHelper.lookupIgnoreCase(snapshot.seoTitle(), language);
        if (seoTitle != null && !seoTitle.isBlank()) {
            return seoTitle.trim();
        }
        return displayTitle;
    }

    private String resolveSeoKeywords(
            com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
            String language) {
        String keywords = LanguageHelper.lookupIgnoreCase(snapshot.seoKeywords(), language);
        return keywords == null || keywords.isBlank() ? null : keywords.trim();
    }

    private String resolvePageDescription(
                                          com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
                                          String localizedAiSummary,
                                          String localizedContent,
                                          String language) {
        String seoDescription = LanguageHelper.lookupIgnoreCase(snapshot.seoDescription(), language);
        if (seoDescription != null && !seoDescription.isBlank()) {
            return seoDescription.trim();
        }

        String localizedSummary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), language);
        if (localizedSummary != null && !localizedSummary.isBlank()) {
            return limitDescription(localizedSummary);
        }

        if (localizedAiSummary != null && !localizedAiSummary.isBlank()) {
            return limitDescription(localizedAiSummary);
        }

        String searchableContent = SearchContentHelper.toSearchableText(localizedContent, snapshot.renderType());
        return limitDescription(searchableContent);
    }

    private String limitDescription(String description) {
        if (description == null) {
            return "";
        }
        String normalizedDescription = description.replaceAll("\\s+", " ").trim();
        if (normalizedDescription.length() <= 160) {
            return normalizedDescription;
        }
        return normalizedDescription.substring(0, 160);
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
                                         String content) {

        if (content == null || content.isBlank()) {
            return new PostBodyView("", false, null);
        }

        PostRenderType effective =
                renderType == null
                        ? PostRenderType.MARKDOWN
                        : renderType;

        return switch (effective) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS ->
                    new PostBodyView(MarkdownHelper.toHtml(content), true, effective);
            case VDITOR ->
                    new PostBodyView(publicContentSanitizer.sanitize(content), true, effective);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL, TUTORIAL_BLOCK ->
                    new PostBodyView(publicContentSanitizer.sanitize(content), true, effective);
        };
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
