package com.biliwind.blog.controller;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.*;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

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

    @GET
    @Path("/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance post(@PathParam("slug") String slug,
                                 @QueryParam("levels") List<Short> levels,
                                 @Context HttpHeaders httpHeaders) {
        return render(slug, null, levels, httpHeaders);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance postWithLang(@PathParam("langCode") String langCode,
                                         @PathParam("slug") String slug,
                                         @QueryParam("levels") List<Short> levels,
                                         @Context HttpHeaders httpHeaders) {
        return render(slug, langCode, levels, httpHeaders);
    }

    private TemplateInstance render(String slug,
                                    String langCode,
                                    List<Short> levels,
                                    HttpHeaders httpHeaders) {

        resolveLanguage(langCode);

        slug = normalizeSlug(slug);

        Post postEntity = Post.find("slug = ?1 and deletedAt is null", slug)
                .firstResult();

        if (postEntity == null) {
            throw new NotFoundException("Post not found: " + slug);
        }

        if (postEntity.visibility == 1) {
            throw new NotFoundException("Post is private");
        }

        String resolvedLang = languageContext.getLang();
        String localizedTitle =
                LanguageHelper.resolveLocalizedValue(postEntity.title, resolvedLang);

        String localizedContent =
                resolveContent(postEntity.currentRevision, resolvedLang);



        // Check if user is logged in
        Long currentUserId = resolveUserIdFromCookie(httpHeaders);

        long postPrice = postAccessService.getPostPrice(postEntity);
        int freeLines = postAccessService.getFreeLines(postEntity);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(currentUserId, postEntity.id);

        // 作者直接绕过购买检查
        boolean isAuthor = currentUserId != null && postEntity.user != null && currentUserId.equals(postEntity.user.id);

        // 安全处理：渲染重构。为了支持 CDN 缓存静态 HTML 且减轻后端渲染压力，
        // 初始下发的 HTML 统一使用剔除保密内容的“安全预览版”，在后端通过 Redis 对此版本进行深度缓存。
        // 对于已经登录且具有查看权限的用户，前端将通过 AJAX 动态请求真实内容并覆盖渲染。
        localizedContent = postAccessService.getCachedPreviewContent(postEntity, resolvedLang, localizedContent, postPrice);

        PostBodyView postBody = resolvePostBody(postEntity.renderType, localizedContent);

        String localizedAiSummary =
                LanguageHelper.resolveLocalizedValue(postEntity.aiSummary, resolvedLang);

        Template template =
                PjaxHelper.isPjaxRequest(httpHeaders)
                        ? postContentTemplate
                        : postTemplate;

        // 全站买断判定
        boolean hasPurchased = isAuthor
                || (postPrice > 0 && maxPointsPaid >= postPrice)
                || postAccessService.hasPurchasedPost(currentUserId, postEntity.id);

        List<PostTag> rawPostTags = PostTag.find("post", postEntity).list();
        List<TagItem> postTags = new java.util.ArrayList<>();
        for (PostTag pt : rawPostTags) {
            postTags.add(new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, resolvedLang), pt.tag.slug));
        }

        List<PostMedia> rawPostMedia = PostMedia.list("post.id = ?1", postEntity.id);
        List<AttachmentView> attachments = new java.util.ArrayList<>();
        for (PostMedia pm : rawPostMedia) {
            if (pm.usageType == 3) { // 3 = 附件
                String attachmentUrl = hasPurchased ? pm.media.url : "";
                long bytes = pm.media.size != null ? pm.media.size : 0;
                String formattedSize = bytes < 1024 * 1024
                        ? (bytes / 1024) + " KB"
                        : String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                attachments.add(new AttachmentView(pm.media.fileName, attachmentUrl, formattedSize));
            }
        }

        return template
                .data("language", resolvedLang)
                .data("postId", postEntity.id)
                .data("postSlug", slug)
                .data("postTitle", localizedTitle == null ? slug : localizedTitle)
                .data("aiSummary", localizedAiSummary)
                .data("aiSummaryStatus", postEntity.aiSummaryStatus != null ? postEntity.aiSummaryStatus.intValue() : 0)
                .data("postBody", postBody.body())
                .data("postBodyHtml", postBody.html())
                .data("publishedAt", postEntity.publishedAt)
                .data("authorName", postEntity.user != null ? postEntity.user.username : "Unknown")
                .data("authorAvatar", postEntity.user != null ? "https://ui-avatars.com/api/?name=" + postEntity.user.username : "/static/img/avatar-default.png")
                .data("postRenderType", postBody.renderType() != null ? postBody.renderType().name() : null)
                .data("postBodyJson", escapeJavaScript(postBody.body()))
                .data("postCategory", postEntity.category != null ? LanguageHelper.resolveLocalizedValue(postEntity.category.name, resolvedLang) : "未分类")
                .data("postPrice", postPrice)
                .data("hasPurchased", hasPurchased)
                .data("postTags", postTags)
                .data("attachments", attachments)
                .data("relatedStoreItems", resolveRelatedStoreItems(postEntity));
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
