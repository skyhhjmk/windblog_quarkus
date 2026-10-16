package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.net.URI;
import java.util.*;

@ApplicationScoped
public class SeoAuditService {
    @Inject CanonicalUrlService canonical;
    @Inject PublicUrlService publicUrl;
    @Inject SeoManagementService seo;

    public String html(Post post, String language) {
        if (post.publishedRevision == null) return "";
        String content = LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, language);
        if (content == null) return "";
        return post.renderType == PostRenderType.MARKDOWN || post.renderType == PostRenderType.FLUTTER_MARKDOWN_PLUS
                ? MarkdownHelper.toHtml(content) : content;
    }

    public Map<String, Object> inspect(Post post, String language, String keyword) {
        Document doc = Jsoup.parseBodyFragment(html(post, language));
        String title = post.seoTitle == null || post.seoTitle.isBlank()
                ? LanguageHelper.resolveLocalizedValue(post.publishedRevision.title, language) : post.seoTitle;
        String description = post.seoDescription == null || post.seoDescription.isBlank()
                ? LanguageHelper.resolveLocalizedValue(post.summary, language) : post.seoDescription;
        List<String> issues = new ArrayList<>();
        int score = 100;
        if (title == null || title.isBlank()) { issues.add("标题为空"); score -= 25; }
        else if (title.length() > 80) { issues.add("标题超过 80 字符，建议精简"); score -= 10; }
        if (description == null || description.isBlank()) { issues.add("未设置独立描述，将使用 AI 摘要或正文回退"); score -= 10; }
        else if (description.length() > 160) { issues.add("描述超过 160 字符，建议精简"); score -= 5; }
        if (doc.text().length() < 200) { issues.add("正文少于 200 字符，请确认内容是否充分"); score -= 10; }
        if (!doc.select("h1").isEmpty()) { issues.add("页面已有文章 H1，正文建议从 H2 开始"); score -= 5; }
        int previous = 1;
        for (var heading : doc.select("h1,h2,h3,h4,h5,h6")) {
            int level = Integer.parseInt(heading.tagName().substring(1));
            if (level > previous + 1) { issues.add("存在跳级标题：" + heading.text()); score -= 5; break; }
            previous = level;
        }
        List<Map<String, String>> images = new ArrayList<>();
        for (var image : doc.select("img,amp-img")) {
            if (!image.hasAttr("alt") || image.attr("alt").isBlank()) {
                issues.add("图片缺少 ALT：" + image.attr("src")); score -= 5;
                images.add(Map.of("src", image.attr("src"), "issue", "缺少 ALT；装饰图片可人工确认留空"));
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            if (title == null || !title.toLowerCase(Locale.ROOT).contains(needle)) { issues.add("标题未包含目标关键词"); score -= 10; }
            if (!doc.text().toLowerCase(Locale.ROOT).contains(needle)) { issues.add("正文未包含目标关键词"); score -= 10; }
            if (description == null || !description.toLowerCase(Locale.ROOT).contains(needle)) { issues.add("描述未包含目标关键词"); score -= 5; }
        }
        return Map.of("postId", post.id, "slug", post.slug, "score", Math.max(0, score), "issues", issues,
                "images", images, "imageCount", doc.select("img,amp-img").size(), "textLength", doc.text().length(),
                "note", "规则检查结果，不代表搜索排名或图片已收录");
    }

    public Set<String> targets(Post post, String language) {
        Set<String> targets = new LinkedHashSet<>();
        for (var anchor : Jsoup.parseBodyFragment(html(post, language)).select("a[href]")) {
            try {
                URI uri = URI.create(anchor.attr("href"));
                if (uri.isOpaque() || (uri.getScheme() != null && !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT)))) continue;
                String host = uri.getHost();
                if (host != null && !host.equalsIgnoreCase(URI.create(canonical.getBaseUrl()).getHost())
                        && !host.equalsIgnoreCase(URI.create(publicUrl.getBaseUrl()).getHost())) continue;
                String path = URI.create(canonical.buildPath("/post/" + post.slug)).resolve(uri).getPath();
                String resolved = seo.redirectTarget(path);
                if (resolved != null) path = resolved;
                path = path.replaceFirst("^/(?i:zh-cn|en-us)(?=/|$)", "");
                if (path.startsWith("/post/")) targets.add(path.substring(6));
            } catch (IllegalArgumentException ignored) { }
        }
        return targets;
    }

    public Map<String, Object> links(int page, String language) {
        int size = 50;
        var query = Post.find("status = ?1 and visibility = 0 and deletedAt is null and publishedRevision is not null order by id", PostStatus.PUBLISHED);
        long total = query.count();
        List<Post> selected = query.page(io.quarkus.panache.common.Page.of(page - 1, size)).list();
        Map<String, Integer> inbound = new HashMap<>();
        Set<String> existing = new HashSet<>();
        // Walk every source in bounded database pages so old posts are included.
        for (int offset = 0; offset * 100L < total; offset++) {
            List<Post> sources = Post.find("status = ?1 and visibility = 0 and deletedAt is null and publishedRevision is not null order by id", PostStatus.PUBLISHED)
                    .page(io.quarkus.panache.common.Page.of(offset, 100)).list();
            for (Post source : sources) {
                existing.add(source.slug);
                for (String lang : com.biliwind.blog.common.constant.LanguageConstant.SUPPORT_LANGS) {
                    for (String target : targets(source, lang)) {
                        if (!source.slug.equals(target)) inbound.merge(target, 1, Integer::sum);
                    }
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Post post : selected) {
            Set<String> outgoing = targets(post, language);
            items.add(Map.of("id", post.id, "slug", post.slug, "incomingReferences", inbound.getOrDefault(post.slug, 0),
                    "noArticleIncomingLinks", inbound.getOrDefault(post.slug, 0) == 0,
                    "outgoing", outgoing, "broken", outgoing.stream().filter(t -> !existing.contains(t)).toList()));
        }
        return Map.of("items", items, "page", page, "total", total,
                "note", "入链按正文链接统计；零入链不代表无法通过首页、分类或 Sitemap 发现");
    }

    public List<Map<String, String>> related(Post post, String language) {
        if (post == null || post.category == null) return List.of();
        List<Post> candidates = Post.find("id <> ?1 and category.id = ?2 and status = ?3 and visibility = 0 "
                + "and deletedAt is null and publishedRevision is not null "
                + "and id not in (select policy.postId from SeoPostPolicy policy where policy.noindex = true) "
                + "and (visibilityRegions is null or cast(visibilityRegions as String) like '%\"global\"%') "
                + "order by publishedAt desc", post.id, post.category.id, PostStatus.PUBLISHED)
                .page(io.quarkus.panache.common.Page.ofSize(5)).list();
        return candidates.stream().map(p -> Map.of("title", Objects.toString(LanguageHelper.resolveLocalizedValue(p.publishedRevision.title, language), p.slug),
                "url", canonical.buildPath(("zh-cn".equalsIgnoreCase(language) ? "" : "/" + language) + "/post/" + p.slug))).toList();
    }
}
