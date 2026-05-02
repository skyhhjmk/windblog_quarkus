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
import java.util.stream.Collectors;

@Path("/")
public class PostController {

    @Inject
    @Location("blog/post.html")
    Template postTemplate;

    @Inject
    @Location("blog/post.content.html")
    Template postContentTemplate;


    @Inject
    LanguageContext languageContext;

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

        // Get PostAccessService via CDI
        com.biliwind.blog.service.PostAccessService postAccessService =
                jakarta.enterprise.inject.spi.CDI.current().select(com.biliwind.blog.service.PostAccessService.class).get();

        // Check if user is logged in
        Long currentUserId = resolveUserIdFromCookie(httpHeaders);

        long postPrice = postAccessService.getPostPrice(postEntity);

        // 作者直接绕过购买检查
        boolean isAuthor = currentUserId != null && postEntity.user != null && currentUserId.equals(postEntity.user.id);
        boolean hasPurchased = isAuthor || postPrice <= 0 || postAccessService.hasPurchasedPost(currentUserId, postEntity.id);

        // Filter content (or strip tags if purchased)
        localizedContent = postAccessService.filterHiddenContent(localizedContent, hasPurchased);

        // Apply free preview if not purchased
        if (!hasPurchased) {
            long price = postAccessService.getPostPrice(postEntity);
            int freeLines = postAccessService.getFreeLines(postEntity);
            // 只有当文章设置了价格（无论是全局价格还是短代码总价）且未购买时，才尝试应用免费行数预览
            if (price > 0) {
                localizedContent = postAccessService.applyFreePreview(localizedContent, freeLines, true);
            }
        }

        PostBodyView postBody = resolvePostBody(postEntity.renderType, localizedContent);

        String localizedAiSummary =
                LanguageHelper.resolveLocalizedValue(postEntity.aiSummary, resolvedLang);

        Template template =
                PjaxHelper.isPjaxRequest(httpHeaders)
                        ? postContentTemplate
                        : postTemplate;

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
                .data("postTags", PostTag.<PostTag>find("post", postEntity).stream()
                        .map(pt -> new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, resolvedLang), pt.tag.slug))
                        .collect(Collectors.toList()))
                .data("attachments", PostMedia.<PostMedia>list("post.id = ?1", postEntity.id).stream()
                        .filter(pm -> pm.usageType == 3) // 3 = 附件
                        .map(pm -> {
                            long bytes = pm.media.size != null ? pm.media.size : 0;
                            String formattedSize = bytes < 1024 * 1024
                                    ? (bytes / 1024) + " KB"
                                    : String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                            return new AttachmentView(pm.media.fileName, pm.media.url, formattedSize);
                        })
                        .collect(Collectors.toList()))
                .data("relatedStoreItems", resolveRelatedStoreItems(postEntity));
    }

    private Long resolveUserIdFromCookie(HttpHeaders headers) {
        jakarta.ws.rs.core.Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        try {
            String[] parts = cookie.getValue().split("\\.");
            if (parts.length != 3) return null;
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> claims = mapper.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            return claims.get("uid") instanceof Number n ? n.longValue() : null;
        } catch (Exception e) {
            return null;
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
