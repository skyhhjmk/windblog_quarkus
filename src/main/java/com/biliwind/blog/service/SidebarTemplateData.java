package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.Tag;
import io.quarkus.arc.Arc;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@TemplateData(namespace = "sidebar")
@ApplicationScoped
public class SidebarTemplateData {

    private static LanguageContext languageContext() {
        return Arc.container().instance(LanguageContext.class).get();
    }

    public static List<PostView> recentPosts(int limit) {
        String lang = languageContext().getLang();
        List<Post> posts = Post.find("deletedAt is null and visibility = 0", Sort.descending("publishedAt").and("createdAt").descending())
                .page(0, limit)
                .list();

        List<PostView> result = new ArrayList<>();
        for (Post p : posts) {
            String title = LanguageHelper.resolveLocalizedValue(p.title, lang);
            result.add(new PostView(p.id, title, p.slug, p.publishedAt, p.createdAt));
        }
        return result;
    }

    public static List<CategoryView> categories() {
        String lang = languageContext().getLang();
        List<Category> categories = Category.listAll(Sort.ascending("path"));

        List<CategoryView> result = new ArrayList<>();
        for (Category c : categories) {
            String name = LanguageHelper.resolveLocalizedValue(c.name, lang);
            result.add(new CategoryView(c.id, name, c.slug, c.postCount));
        }
        return result;
    }

    public static List<TagView> tags() {
        String lang = languageContext().getLang();
        List<Tag> tags = Tag.listAll();

        List<TagView> result = new ArrayList<>();
        for (Tag t : tags) {
            String name = LanguageHelper.resolveLocalizedValue(t.name, lang);
            result.add(new TagView(t.id, name, t.slug));
        }
        return result;
    }

    public static StatsView stats() {
        long postCount = Post.count("deletedAt is null and visibility = 0");
        long categoryCount = Category.count();
        long tagCount = Tag.count();
        long commentCount = Comment.count();

        return new StatsView(postCount, categoryCount, tagCount, commentCount);
    }

    public record PostView(Long id, String title, String slug, java.time.OffsetDateTime publishedAt,
                           java.time.OffsetDateTime createdAt) {
    }

    public record CategoryView(Long id, String name, String slug, Long count) {
    }

    public record TagView(Long id, String name, String slug) {
    }

    public record StatsView(long posts, long categories, long tags, long comments) {
    }
}
