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
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/")
public class PostController {

    private static final Map<String, String> CONTENT_DECLARATION_LABELS = Map.of(
            "EXPLICIT_AND_EMBEDDED_ADVERTISING", "包含显式广告和植入式广告",
            "AI_GENERATED_CONTENT", "存在 AI 生成内容",
            "SUBJECTIVE_VIEWPOINTS", "存在主观观点",
            "AUTOMATION_USE_ALLOWED", "可用于自动化程序",
            "CC_BY_NC_4_0", "CC BY-NC 4.0");

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
    com.biliwind.blog.service.ConfigManager configManager;

    @Inject
    com.biliwind.blog.service.PublicUrlService publicUrlService;

    @Inject
    com.biliwind.blog.service.AmpPageService ampPageService;

    @Inject
    com.biliwind.blog.service.MediaAccessService mediaAccessService;

    @Inject
    com.biliwind.blog.service.repost.AffiliateContentRenderService affiliateContentRenderService;

    @Inject
    com.biliwind.blog.service.repost.RepostLicenseService repostLicenseService;

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
                         @Context HttpHeaders httpHeaders,
                         @Context Request request) {
        return render(slug, null, levels, httpHeaders, request);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public Response postWithLang(@PathParam("langCode") String langCode,
                                 @PathParam("slug") String slug,
                                 @QueryParam("levels") List<Short> levels,
                                 @Context HttpHeaders httpHeaders,
                                 @Context Request request) {
        return render(slug, langCode, levels, httpHeaders, request);
    }

    private Response render(String slug,
                            String langCode,
                            List<Short> levels,
                            HttpHeaders httpHeaders,
                            Request request) {

        resolveLanguage(langCode);

        slug = normalizeSlug(slug);

        String currentRegion = regionContext.getCurrentRegion().getCode();
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);

        Optional<com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot> snapshotResult =
                publicCacheRefreshService.findPublishedSnapshot(slug, currentRegion);
        if (snapshotResult.isEmpty()) {
            throw new NotFoundException("Post not found: " + slug);
        }
        com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot =
                snapshotResult.get();

        String resolvedLang = languageContext.getLang();

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
                + (pjaxRequest ? "_pjax" : "_full");
        EntityTag etag = new EntityTag(etagValue);
        boolean protectedPage = snapshot.visibility() == 2;

        // 评估客户端提供的 If-None-Match 首部
        if (!protectedPage) {
            Response.ResponseBuilder responseBuilder = request.evaluatePreconditions(etag);
            if (responseBuilder != null) {
                CacheControl cacheControl = new CacheControl();
                cacheControl.setMaxAge(60); // 缓存 60 秒
                return responseBuilder.cacheControl(cacheControl)
                        .header("Vary", "Accept-Language, Cookie")
                        .build();
            }
        }

        String localizedTitle = LanguageHelper.resolveLocalizedValue(snapshot.title(), resolvedLang);
        Long requestUserId = resolveUserId(httpHeaders);
        String deviceId = httpHeaders.getHeaderString("X-Device-Id");
        String localizedContent = resolveSnapshotContent(snapshot, resolvedLang);
        boolean hasPurchased = false;
        if (protectedPage) {
            Post protectedPost = Post.find(
                    "id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                    snapshot.postId(), PostStatus.PUBLISHED).firstResult();
            PostAccessPolicy.Decision access = postAccessPolicy.evaluate(
                    protectedPost, requestUserId, null,
                    readPasswordTicket(httpHeaders, snapshot.postId()), deviceId);
            if (access.allowed() && protectedPost != null) {
                localizedContent = resolveContent(protectedPost.publishedRevision, resolvedLang);
                boolean author = postAccessPolicy.isAuthor(protectedPost, requestUserId);
                hasPurchased = author || postAccessService.hasPurchasedPost(requestUserId, protectedPost.id);
                localizedContent = postAccessService.rewriteProtectedMediaReferences(
                        protectedPost.id, localizedContent, requestUserId, hasPurchased, deviceId);
            }
        }

        long postPrice = snapshot.postPrice();
        String ampUrl = null;
        if (ampPageService.isEnabled() && snapshot.visibility() == 0 && postPrice <= 0) {
            ampUrl = buildAmpUrl(slug, resolvedLang);
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
        if (snapshot.attachments() != null) {
            for (com.biliwind.blog.service.PublicCacheRefreshService.PublicAttachmentSnapshot attachment
                    : snapshot.attachments()) {
                if (!isAttachmentVisible(attachment, currentRegion)) {
                    continue;
                }
                long bytes = attachment.size() == null ? 0L : attachment.size();
                String formattedSize;
                if (bytes < 1024 * 1024) {
                    formattedSize = (bytes / 1024) + " KB";
                } else {
                    formattedSize = String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                }
                attachments.add(new AttachmentView(attachment.fileName(), "", formattedSize));
            }
        }

        String displayTitle = localizedTitle;
        if (displayTitle == null) {
            displayTitle = slug;
        }
        String seoTitle = resolveSeoTitle(snapshot, displayTitle);
        String pageDescription = resolvePageDescription(snapshot, localizedAiSummary, localizedContent, resolvedLang);

        int aiSummaryStatusVal = 0;
        if (snapshot.aiSummaryStatus() != null) {
            aiSummaryStatusVal = snapshot.aiSummaryStatus().intValue();
        }
        String authorName = snapshot.authorName();
        if (authorName == null || authorName.isBlank()) {
            authorName = "Unknown";
        }

        String authorAvatar = "/static/img/avatar-default.png";

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

        TemplateInstance templateInstance = template
                .data("language", resolvedLang)
                .data("postSlug", slug)
                .data("postTitle", displayTitle)
                .data("pageTitle", seoTitle)
                .data("pageDescription", pageDescription)
                .data("pageKeywords", snapshot.seoKeywords())
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
                .data("postPrice", postPrice)
                // Public pages use the read model; protected pages are no-store and may
                // render authorized content loaded from the published revision.
                .data("hasPurchased", hasPurchased)
                .data("postTags", postTags)
                .data("contentDeclarations", resolveContentDeclarationLabels(snapshot.contentDeclarations()))
                .data("attachments", attachments)
                .data("relatedStoreItems", snapshot.relatedStoreItems())
                .data("repostOriginalUrl", repostLicenseService.buildPostUrl(slug))
                .data("canonicalUrl", buildCanonicalUrl(slug))
                .data("ampUrl", ampUrl)
                .data("ogData", buildOgData(slug, seoTitle, pageDescription))
                .data("jsonLd", buildJsonLd(snapshot, seoTitle, pageDescription, slug));

        Response.ResponseBuilder responseBuilder = Response.ok(templateInstance)
                .header("Vary", "Accept-Language, Cookie");
        if (protectedPage) {
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

    private String buildCanonicalUrl(String slug) {
        return publicUrlService.buildPath("/post/" + slug);
    }

    private String buildAmpUrl(String slug, String language) {
        if (LanguageConstant.DEFAULT_LANG.equalsIgnoreCase(language)) {
            return publicUrlService.buildPath("/amp/post/" + slug);
        }
        return publicUrlService.buildPath("/" + language + "/amp/post/" + slug);
    }

    private String resolveSeoTitle(
            com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
            String displayTitle) {
        if (snapshot.seoTitle() != null && !snapshot.seoTitle().isBlank()) {
            return snapshot.seoTitle().trim();
        }
        return displayTitle;
    }

    private String resolvePageDescription(
                                          com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
                                          String localizedAiSummary,
                                          String localizedContent,
                                          String language) {
        if (snapshot.seoDescription() != null && !snapshot.seoDescription().isBlank()) {
            return snapshot.seoDescription().trim();
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

    private java.util.Map<String, String> buildOgData(String slug, String title, String summary) {
        java.util.Map<String, String> og = new java.util.HashMap<>();
        og.put("og:title", title);
        og.put("og:description", summary != null ? summary : "");
        og.put("og:type", "article");
        og.put("og:url", buildCanonicalUrl(slug));

        // Try to find a featured image
        String imageUrl = configManager.getString("appearance", "logo_url", "/logo.png");
        // In a real scenario, you'd check post.featuredImage or first image in content
        og.put("og:image", imageUrl);

        og.put("twitter:card", "summary_large_image");
        og.put("twitter:title", title);
        og.put("twitter:description", summary != null ? summary : "");
        og.put("twitter:image", imageUrl);

        return og;
    }

    private String buildJsonLd(
            com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot snapshot,
            String title,
            String summary,
            String slug) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
            root.put("@context", "https://schema.org");
            root.put("@type", "BlogPosting");
            root.put("headline", title);
            root.put("description", summary != null ? summary : "");
            root.put("url", buildCanonicalUrl(slug));
            root.put("datePublished", snapshot.publishedAt() != null ? snapshot.publishedAt().toString() : "");
            root.put("dateModified", snapshot.updatedAt() != null ? snapshot.updatedAt().toString() : "");

            com.fasterxml.jackson.databind.node.ObjectNode author = root.putObject("author");
            author.put("@type", "Person");
            author.put("name", snapshot.authorName() == null ? "Unknown" : snapshot.authorName());

            com.fasterxml.jackson.databind.node.ObjectNode publisher = root.putObject("publisher");
            publisher.put("@type", "Organization");
            publisher.put("name", configManager.getString("site_info", "title", "WindBlog"));
            com.fasterxml.jackson.databind.node.ObjectNode logo = publisher.putObject("logo");
            logo.put("@type", "ImageObject");
            logo.put("url", configManager.getString("appearance", "logo_url", "/logo.png"));

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

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
            languageContext.setLang(LanguageConstant.DEFAULT_LANG);
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

    private boolean isAttachmentVisible(
            com.biliwind.blog.service.PublicCacheRefreshService.PublicAttachmentSnapshot attachment,
            String regionCode) {
        if (containsRegion(attachment.hiddenRegions(), regionCode)) {
            return false;
        }
        List<String> visibleRegions = attachment.visibilityRegions();
        if (visibleRegions == null || visibleRegions.isEmpty()) {
            return true;
        }
        return containsRegion(visibleRegions, "GLOBAL") || containsRegion(visibleRegions, regionCode);
    }

    private boolean containsRegion(List<String> regions, String expected) {
        if (regions == null || expected == null) {
            return false;
        }
        for (String region : regions) {
            if (region != null && expected.equalsIgnoreCase(region.trim())) {
                return true;
            }
        }
        return false;
    }

    private record PostBodyView(String body, boolean html, PostRenderType renderType) {}

    public record AttachmentView(String fileName, String downloadUrl, String formattedSize) {
    }

}
