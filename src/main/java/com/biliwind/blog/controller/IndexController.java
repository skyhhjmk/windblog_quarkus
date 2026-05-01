package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Index controller
 */
@Path("/")
public class IndexController {

    private static final int PAGE_SIZE = 10;

    @Inject
    @Location("blog/index.html")
    Template index;

    @Inject
    @Location("blog/index.content.html")
    Template indexContent;

    @Inject
    LanguageContext languageContext;

    /**
     * @param httpHeaders HttpHeaders
     * @return Index page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@Context HttpHeaders httpHeaders) {
        return subPage(1, httpHeaders);
    }

    /**
     * 首页（文章列表）分页
     *
     * @param subPage page number
     * @param httpHeaders HttpHeaders
     * @return subPage page
     */
    @Path("/page/{subPage}")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance subPage(@PathParam("subPage") Integer subPage,
                                    @Context HttpHeaders httpHeaders) {
        if (subPage == null || subPage < 1) {
            subPage = 1;
        }

        String lang = languageContext.getLang();
        var postQuery =
                Post.find("status = :status and deletedAt is null order by publishedAt desc nulls last, createdAt desc",
                        Parameters.with("status", PostStatus.PUBLISHED).map());
        long total = postQuery.count();
        List<Post> posts = postQuery.page(Page.of(subPage - 1, PAGE_SIZE)).list();
        long totalPages = total == 0 ? 1 : (long) Math.ceil((double) total / PAGE_SIZE);

        List<IndexPostItem> postItems = posts.stream()
                .map(post -> toIndexItem(post, lang))
                .toList();

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? indexContent : index;

        return template
                .data("language", lang)
                .data("subPage", subPage)
                .data("pageSize", PAGE_SIZE)
                .data("totalPosts", total)
                .data("totalPages", totalPages)
                .data("hasPrevPage", subPage > 1)
                .data("hasNextPage", subPage < totalPages)
                .data("prevPage", Math.max(1, subPage - 1))
                .data("nextPage", Math.min(totalPages, subPage + 1))
                .data("posts", postItems);
    }

    private IndexPostItem toIndexItem(Post post, String lang) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "暂无摘要";
        }

        Category cat = post.category;
        String categoryName = cat != null ? LanguageHelper.resolveLocalizedValue(cat.name, lang) : "未分类";

        List<TagItem> tags = PostTag.<PostTag>find("post", post).stream()
                .map(pt -> new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, lang), pt.tag.slug))
                .toList();

        return new IndexPostItem(
                post.id,
                post.slug,
                title,
                summary,
                post.aiSummary,
                post.aiSummaryStatus != null ? post.aiSummaryStatus.intValue() : 0,
                post.publishedAt,
                post.createdAt,
                categoryName,
                post.user != null ? post.user.username : "Unknown",
                tags
        );
    }

    public record TagItem(String name, String slug) {}

    public record IndexPostItem(
            Long id,
            String slug,
            String title,
            String summary,
            Map<String, String> aiSummary,
            Integer aiSummaryStatus,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            String categoryName,
            String authorName,
            List<TagItem> tags
    ) {
    }
}
