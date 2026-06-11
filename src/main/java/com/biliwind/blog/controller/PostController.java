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
import com.biliwind.blog.service.storage.VariantType;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.util.List;

@Path("/")
public class PostController {

    @Inject
    @Location("blog/post.html")
    Template postTemplate;

    @Inject
    @Location("blog/post.content.html")
    Template postContentTemplate;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @Inject
    LanguageContext languageContext;

    @Inject
    com.biliwind.blog.service.PostAccessService postAccessService;

    @Inject
    RegionContext regionContext;

    @Inject
    com.biliwind.blog.service.ConfigManager configManager;

    @Inject
    com.biliwind.blog.service.storage.StorageService storageService;

    @Inject
    com.biliwind.blog.service.MediaAccessService mediaAccessService;

    @Inject
    com.biliwind.blog.service.repost.AffiliateContentRenderService affiliateContentRenderService;

    @Inject
    com.biliwind.blog.service.repost.RepostLicenseService repostLicenseService;

    @Inject
    com.biliwind.blog.service.link.ArticleExternalLinkService articleExternalLinkService;

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

        // 核心过滤逻辑: visibilityRegions 为空或者是包含当前区域
        Post postEntity = Post.find(
                "slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null and (visibilityRegions is null or cast(visibilityRegions as String) like ?3)",
                slug, PostStatus.PUBLISHED, "%\"" + currentRegion + "\"%"
        ).firstResult();

        if (postEntity == null) {
            throw new NotFoundException("Post not found: " + slug);
        }

        if (postEntity.visibility == 1) {
            throw new NotFoundException("Post is private");
        }

        String resolvedLang = languageContext.getLang();

        // 计算 ETag 逻辑。使用显式 if/else，禁止三目
        java.time.OffsetDateTime lastUpdated = postEntity.updatedAt;
        if (lastUpdated == null) {
            lastUpdated = postEntity.createdAt;
        }
        if (lastUpdated == null) {
            lastUpdated = java.time.OffsetDateTime.now();
        }

        long epoch = lastUpdated.toEpochSecond();
        String etagValue = postEntity.id.toString() + "_" + epoch + "_" + resolvedLang + "_" + currentRegion;
        EntityTag etag = new EntityTag(etagValue);

        // 评估客户端提供的 If-None-Match 首部
        Response.ResponseBuilder responseBuilder = request.evaluatePreconditions(etag);
        if (responseBuilder != null) {
            CacheControl cacheControl = new CacheControl();
            cacheControl.setMaxAge(60); // 缓存 60 秒
            return responseBuilder.cacheControl(cacheControl).build();
        }

        String localizedTitle =
                LanguageHelper.resolveLocalizedValue(postEntity.title, resolvedLang);

        String localizedContent =
                resolveContent(postEntity.publishedRevision, resolvedLang);

        // Check if user is logged in
        Long currentUserId = resolveUserIdFromCookie(httpHeaders);

        long postPrice = postAccessService.getPostPrice(postEntity);
        int freeLines = postAccessService.getFreeLines(postEntity);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(currentUserId, postEntity.id);

        // 作者直接绕过购买检查
        boolean isAuthor = false;
        if (currentUserId != null) {
            if (postEntity.user != null) {
                if (currentUserId.equals(postEntity.user.id)) {
                    isAuthor = true;
                }
            }
        }

        // 安全处理：渲染重构。为了支持 CDN 缓存静态 HTML 且减轻后端渲染压力，
        // 初始下发的 HTML 统一使用剔除保密内容的“安全预览版”，在后端通过 Redis 对此版本进行深度缓存。
        // 对于已经登录且具有查看权限的用户，前端将通过 AJAX 动态请求真实内容并覆盖渲染。
        localizedContent = postAccessService.getCachedPreviewContent(postEntity, resolvedLang, localizedContent, postPrice);

        PostBodyView postBody = resolvePostBody(postEntity.renderType, localizedContent);
        if (postBody.html()) {
            String rewrittenPostBody = affiliateContentRenderService.rewriteCommercialLinks(postEntity, postBody.body());
            rewrittenPostBody = articleExternalLinkService.rewriteArticleExternalLinks(rewrittenPostBody);
            postBody = new PostBodyView(rewrittenPostBody, true, postBody.renderType());
        }

        String localizedAiSummary =
                LanguageHelper.resolveLocalizedValue(postEntity.aiSummary, resolvedLang);

        Template template = null;
        if (PjaxHelper.isPjaxRequest(httpHeaders)) {
            template = postContentTemplate;
        } else {
            template = postTemplate;
        }

        // 全站买断判定
        boolean hasPurchased = false;
        if (isAuthor) {
            hasPurchased = true;
        } else {
            if (postPrice > 0) {
                if (maxPointsPaid >= postPrice) {
                    hasPurchased = true;
                }
            }
        }
        if (hasPurchased == false) {
            if (postAccessService.hasPurchasedPost(currentUserId, postEntity.id)) {
                hasPurchased = true;
            }
        }

        List<PostTag> rawPostTags = PostTag.find("post", postEntity).list();
        List<TagItem> postTags = new java.util.ArrayList<>();
        for (PostTag pt : rawPostTags) {
            postTags.add(new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, resolvedLang), pt.tag.slug));
        }

        List<PostMedia> rawPostMedia = PostMedia.list("post.id = ?1", postEntity.id);
        List<AttachmentView> attachments = new java.util.ArrayList<>();
        for (PostMedia pm : rawPostMedia) {
            if (pm.usageType == 3) { // 3 = 附件
                if (!mediaAccessService.canAccess(pm.media, regionContext.getCurrentRegion())) {
                    continue;
                }
                String attachmentUrl = "";
                if (hasPurchased) {
                    attachmentUrl = storageService.getBestSignedUrl(pm.media, VariantType.ORIGINAL, java.time.Duration.ofMinutes(10));
                    if (attachmentUrl == null) {
                        attachmentUrl = pm.media.url;
                    }
                }
                long bytes = 0;
                if (pm.media.size != null) {
                    bytes = pm.media.size;
                }
                String formattedSize = "";
                if (bytes < 1024 * 1024) {
                    formattedSize = (bytes / 1024) + " KB";
                } else {
                    formattedSize = String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                }
                attachments.add(new AttachmentView(pm.media.fileName, attachmentUrl, formattedSize));
            }
        }

        String displayTitle = localizedTitle;
        if (displayTitle == null) {
            displayTitle = slug;
        }
        String seoTitle = resolveSeoTitle(postEntity, displayTitle);
        String pageDescription = resolvePageDescription(postEntity, localizedAiSummary, localizedContent, resolvedLang);

        int aiSummaryStatusVal = 0;
        if (postEntity.aiSummaryStatus != null) {
            aiSummaryStatusVal = postEntity.aiSummaryStatus.intValue();
        }

        String authorName = "Unknown";
        if (postEntity.user != null) {
            authorName = postEntity.user.username;
        }

        String authorAvatar = "/static/img/avatar-default.png";
        if (postEntity.user != null) {
            authorAvatar = "https://ui-avatars.com/api/?name=" + postEntity.user.username;
        }

        String renderTypeName = null;
        if (postBody.renderType() != null) {
            renderTypeName = postBody.renderType().name();
        }

        String postCategory = "未分类";
        if (postEntity.category != null) {
            postCategory = LanguageHelper.resolveLocalizedValue(postEntity.category.name, resolvedLang);
        }

        String postCategorySlug = "uncategorized";
        if (postEntity.category != null) {
            postCategorySlug = postEntity.category.slug;
        }

        TemplateInstance templateInstance = template
                .data("language", resolvedLang)
                .data("postId", postEntity.id)
                .data("postSlug", slug)
                .data("postTitle", displayTitle)
                .data("pageTitle", seoTitle)
                .data("pageDescription", pageDescription)
                .data("pageKeywords", postEntity.seoKeywords)
                .data("aiSummary", localizedAiSummary)
                .data("aiSummaryStatus", aiSummaryStatusVal)
                .data("postBody", postBody.body())
                .data("postBodyHtml", postBody.html())
                .data("publishedAt", postEntity.publishedAt)
                .data("authorName", authorName)
                .data("authorAvatar", authorAvatar)
                .data("postRenderType", renderTypeName)
                .data("postBodyJson", escapeJavaScript(postBody.body()))
                .data("postCategory", postCategory)
                .data("postCategorySlug", postCategorySlug)
                .data("postPrice", postPrice)
                .data("hasPurchased", hasPurchased)
                .data("repostUserLoggedIn", currentUserId != null)
                .data("postTags", postTags)
                .data("attachments", attachments)
                .data("relatedStoreItems", resolveRelatedStoreItems(postEntity))
                .data("repostOriginalUrl", repostLicenseService.buildPostUrl(postEntity))
                .data("canonicalUrl", buildCanonicalUrl(slug))
                .data("ogData", buildOgData(postEntity, seoTitle, pageDescription, resolvedLang))
                .data("jsonLd", buildJsonLd(postEntity, seoTitle, pageDescription, resolvedLang));

        CacheControl cacheControl = new CacheControl();
        cacheControl.setMaxAge(60); // 缓存 60 秒

        return Response.ok(templateInstance)
                .tag(etag)
                .cacheControl(cacheControl)
                .build();
    }

    private String buildCanonicalUrl(String slug) {
        String baseUrl = configManager.getString("site_info", "url", "http://localhost:8080");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/post/" + slug;
    }

    private String resolveSeoTitle(Post post, String displayTitle) {
        if (post.seoTitle != null && !post.seoTitle.isBlank()) {
            return post.seoTitle.trim();
        }
        return displayTitle;
    }

    private String resolvePageDescription(Post post,
                                          String localizedAiSummary,
                                          String localizedContent,
                                          String language) {
        if (post.seoDescription != null && !post.seoDescription.isBlank()) {
            return post.seoDescription.trim();
        }

        String localizedSummary = LanguageHelper.resolveLocalizedValue(post.summary, language);
        if (localizedSummary != null && !localizedSummary.isBlank()) {
            return limitDescription(localizedSummary);
        }

        if (localizedAiSummary != null && !localizedAiSummary.isBlank()) {
            return limitDescription(localizedAiSummary);
        }

        String searchableContent = SearchContentHelper.toSearchableText(localizedContent, post.renderType);
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

    private java.util.Map<String, String> buildOgData(Post post, String title, String summary, String lang) {
        java.util.Map<String, String> og = new java.util.HashMap<>();
        og.put("og:title", title);
        og.put("og:description", summary != null ? summary : "");
        og.put("og:type", "article");
        og.put("og:url", buildCanonicalUrl(post.slug));

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

    private String buildJsonLd(Post post, String title, String summary, String lang) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
            root.put("@context", "https://schema.org");
            root.put("@type", "BlogPosting");
            root.put("headline", title);
            root.put("description", summary != null ? summary : "");
            root.put("url", buildCanonicalUrl(post.slug));
            root.put("datePublished", post.publishedAt != null ? post.publishedAt.toString() : "");
            root.put("dateModified", post.updatedAt != null ? post.updatedAt.toString() : "");

            com.fasterxml.jackson.databind.node.ObjectNode author = root.putObject("author");
            author.put("@type", "Person");
            author.put("name", post.user != null ? post.user.username : "Unknown");

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

    private Long resolveUserIdFromCookie(HttpHeaders headers) {
        jakarta.ws.rs.core.Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        com.biliwind.blog.common.security.UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        if (verified == null) {
            return null;
        }
        return verified.uid();
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
                    new PostBodyView(content, false, effective);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL, TUTORIAL_BLOCK ->
                    new PostBodyView(content, true, effective);
        };
    }

    private List<com.biliwind.blog.model.StoreItem> resolveRelatedStoreItems(Post post) {
        if (post.extraInfo == null) return java.util.Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.convertValue(post.extraInfo, com.fasterxml.jackson.databind.JsonNode.class);
            if (node.has("related_store_items") && node.get("related_store_items").isArray()) {
                java.util.List<Long> ids = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode idNode : node.get("related_store_items")) {
                    ids.add(idNode.asLong());
                }
                if (!ids.isEmpty()) {
                    return com.biliwind.blog.model.StoreItem.list("id in ?1", ids);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return java.util.Collections.emptyList();
    }

    private record PostBodyView(String body, boolean html, PostRenderType renderType) {}

    public record AttachmentView(String fileName, String url, String formattedSize) {
    }

}
