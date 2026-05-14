package com.biliwind.blog.edge;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.PostAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@Path("/")
public class EdgeBlogController {
    private static final Logger log = LoggerFactory.getLogger(EdgeBlogController.class);

    @Inject
    @Location("blog/post.html")
    Template postTemplate;

    @Inject
    @Location("blog/post.content.html")
    Template postContentTemplate;


    @Inject
    LanguageContext languageContext;

    @Inject
    PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "edge.node.region", defaultValue = "global")
    String region;

    @GET
    @Path("/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance post(@PathParam("slug") String slug,
                                 @QueryParam("levels") List<Short> levels,
                                 @Context HttpHeaders httpHeaders) {
        return renderPost(slug, null, levels, httpHeaders);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance postWithLang(@PathParam("langCode") String langCode,
                                         @PathParam("slug") String slug,
                                         @QueryParam("levels") List<Short> levels,
                                         @Context HttpHeaders httpHeaders) {
        return renderPost(slug, langCode, levels, httpHeaders);
    }

    private TemplateInstance renderPost(String slug, String langCode, List<Short> levels, HttpHeaders httpHeaders) {
        resolveLanguage(langCode);
        String resolvedLang = languageContext.getLang();

        slug = normalizeSlug(slug);

        Post postEntity = Post.find("slug = ?1 and deletedAt is null and (visibilityRegions is null or cast(visibilityRegions as String) like ?2)",
                slug, "%\"" + region + "\"%").firstResult();

        if (postEntity == null) {
            throw new NotFoundException("Post not found: " + slug);
        }

        if (postEntity.visibility == 1) {
            throw new NotFoundException("Post is private");
        }

        String localizedTitle = LanguageHelper.resolveLocalizedValue(postEntity.title, resolvedLang);
        String rawContent = "";
        if (postEntity.currentRevision != null) {
            rawContent = LanguageHelper.resolveLocalizedValue(postEntity.currentRevision.contentMarkdown, resolvedLang);
        }

        Long currentUserId = resolveUserIdFromCookie(httpHeaders);
        long postPrice = postAccessService.getPostPrice(postEntity);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(currentUserId, postEntity.id);

        boolean isAuthor = currentUserId != null && postEntity.user != null && currentUserId.equals(postEntity.user.id);
        boolean hasPurchased = isAuthor
                || (postPrice > 0 && maxPointsPaid >= postPrice)
                || postAccessService.hasPurchasedPost(currentUserId, postEntity.id);

        String processedContent = postAccessService.getCachedPreviewContent(postEntity, resolvedLang, rawContent, postPrice);
        PostBodyView postBody = resolvePostBody(postEntity.renderType, processedContent);

        String localizedAiSummary = LanguageHelper.resolveLocalizedValue(postEntity.aiSummary, resolvedLang);

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? postContentTemplate : postTemplate;

        // Tags
        List<PostTag> rawPostTags = PostTag.find("post", postEntity).list();
        List<TagItem> postTags = new ArrayList<>();
        for (PostTag pt : rawPostTags) {
            postTags.add(new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, resolvedLang), pt.tag.slug));
        }

        // Attachments
        List<PostMedia> rawPostMedia = PostMedia.list("post.id = ?1", postEntity.id);
        List<AttachmentView> attachments = new ArrayList<>();
        for (PostMedia pm : rawPostMedia) {
            if (pm.usageType == 3) { // 3 = Attachment
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
                .data("postCategory", postEntity.category != null ? LanguageHelper.resolveLocalizedValue(postEntity.category.name, resolvedLang) : "未分类")
                .data("postCategorySlug", postEntity.category != null ? postEntity.category.slug : "uncategorized")
                .data("postPrice", postPrice)
                .data("hasPurchased", hasPurchased)
                .data("postTags", postTags)
                .data("attachments", attachments)
                .data("relatedStoreItems", resolveRelatedStoreItems(postEntity));
    }

    private String normalizeSlug(String slug) {
        if (slug != null && slug.toLowerCase().endsWith(".html")) {
            slug = slug.substring(0, slug.length() - 5);
        }
        return slug == null || slug.isBlank() ? "untitled" : slug;
    }

    private void resolveLanguage(String langCode) {
        if (langCode == null || langCode.isBlank()) {
            languageContext.setLang(LanguageConstant.DEFAULT_LANG);
            return;
        }
        String normalized = LanguageHelper.normalizeToSupportedLang(langCode);
        languageContext.setLang(normalized == null ? LanguageConstant.DEFAULT_LANG : normalized);
    }

    private Long resolveUserIdFromCookie(HttpHeaders headers) {
        var cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null) return null;
        var verified = tokenVerifier.verify(cookie.getValue());
        return verified != null ? verified.uid() : null;
    }

    private PostBodyView resolvePostBody(PostRenderType renderType, String content) {
        if (content == null || content.isBlank()) {
            return new PostBodyView("", false);
        }
        PostRenderType effective = renderType == null ? PostRenderType.MARKDOWN : renderType;
        return switch (effective) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS -> new PostBodyView(MarkdownHelper.toHtml(content), true);
            default -> new PostBodyView(content, true);
        };
    }

    private List<StoreItem> resolveRelatedStoreItems(Post post) {
        if (post.extraInfo == null) return Collections.emptyList();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.convertValue(post.extraInfo, JsonNode.class);
            if (node.has("related_store_items") && node.get("related_store_items").isArray()) {
                List<Long> ids = new ArrayList<>();
                for (JsonNode idNode : node.get("related_store_items")) {
                    ids.add(idNode.asLong());
                }
                if (!ids.isEmpty()) {
                    return StoreItem.list("id in ?1", ids);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return Collections.emptyList();
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public record TagItem(String name, String slug) {
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public record AttachmentView(String fileName, String url, String formattedSize) {
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    private record PostBodyView(String body, boolean html) {
    }
}

