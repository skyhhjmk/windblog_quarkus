package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.model.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.jsoup.Jsoup;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

@ApplicationScoped
public class InternalLinkGraphService {
    private static final int MAX_CONTENT_CHARS = 1_000_000;
    @Inject ConfigManager config;
    @Inject CanonicalUrlService canonical;
    @Inject PublicUrlService publicUrl;
    @Inject RegionRuleService regions;
    @Inject PublicCacheRefreshService publicCache;
    @Inject PublicContentSanitizer sanitizer;

    public boolean enabled() {
        return config.getBoolean("seo", "internal_link_graph_enabled", false);
    }

    public Graph publicGraph(String slug, String language, String region) {
        if (!enabled()) throw new NotFoundException();
        var aliases = aliases();
        var hosts = hosts();
        // The anonymous graph always uses the public preview, even for logged-in purchasers.
        return traverse(slug, 1, 80, target -> publicCache.findPublishedSnapshot(target, region)
                .filter(snapshot -> snapshot.visibility() == 0)
                .map(snapshot -> {
                    String content = LanguageHelper.resolveLocalizedValue(snapshot.previewContent(), language);
                    return source(snapshot.postId(), snapshot.slug(), snapshot.title(), content,
                            snapshot.renderType(), language, hosts, aliases);
                }).orElse(null));
    }

    public Graph adminGraph(Long id, String language, int depth, int maxNodes) {
        if (!enabled()) throw new NotFoundException();
        return adminGraphUnchecked(id, language, depth, maxNodes);
    }

    /** Same admin traversal without the public internal-link-graph feature flag. */
    public Graph adminGraphUnchecked(Long id, String language, int depth, int maxNodes) {
        if (depth < 1 || depth > 8 || maxNodes < 2 || maxNodes > 300)
            throw new BadRequestException("递归深度须为 1–8，节点上限须为 2–300");
        Post root = Post.find("id = ?1 and status = ?2 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                id, PostStatus.PUBLISHED).firstResult();
        if (root == null) throw new NotFoundException("只能分析已发布的公开文章");
        var aliases = aliases();
        var hosts = hosts();
        return traverse(root.slug, depth, maxNodes, slug -> {
            Post post = Post.find("slug = ?1 and status = ?2 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                    slug, PostStatus.PUBLISHED).firstResult();
            if (post == null) return null;
            return source(post.id, post.slug, post.publishedRevision.title,
                    LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, language),
                    post.renderType, language, hosts, aliases);
        });
    }

    private Source source(Long id, String slug, Map<String, String> titles, String content,
                          PostRenderType type, String language, Set<String> hosts, Map<String, String> aliases) {
        String title = LanguageHelper.resolveLocalizedValue(titles, language);
        boolean clipped = content != null && content.length() > MAX_CONTENT_CHARS;
        if (clipped) content = content.substring(0, MAX_CONTENT_CHARS);
        String html = type == null || type == PostRenderType.MARKDOWN || type == PostRenderType.FLUTTER_MARKDOWN_PLUS
                ? MarkdownHelper.toHtml(content) : sanitizer.sanitize(content);
        String path = ("zh-cn".equalsIgnoreCase(language) ? "" : "/" + language) + "/post/" + encode(slug);
        String url = canonical.buildPath(path);
        return new Source(id, slug, title == null || title.isBlank() ? slug : title,
                url, extractTargets(html, url, hosts, aliases), clipped);
    }

    private Map<String, String> aliases() {
        Map<String, String> result = new HashMap<>();
        for (SeoRedirect redirect : SeoRedirect.<SeoRedirect>list("enabled = true")) result.put(redirect.source, redirect.target);
        return result;
    }

    private Set<String> hosts() {
        Set<String> result = new HashSet<>();
        addHost(result, canonical.getBaseUrl());
        addHost(result, publicUrl.getBaseUrl());
        for (var domain : regions.siteDomains()) {
            addHost(result, domain.url());
            String host = URI.create(domain.url()).getHost();
            if (host != null && host.startsWith("www.")) result.add(host.substring(4).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private void addHost(Set<String> result, String url) {
        String host = URI.create(url).getHost();
        if (host != null) result.add(host.toLowerCase(Locale.ROOT));
    }

    static Set<String> extractTargets(String html, String base, Set<String> hosts, Map<String, String> aliases) {
        Set<String> result = new LinkedHashSet<>();
        for (var anchor : Jsoup.parseBodyFragment(html == null ? "" : html).select("a[href]")) {
            try {
                URI uri = URI.create(base).resolve(URI.create(anchor.attr("href"))).normalize();
                if (uri.isOpaque() || uri.getUserInfo() != null || uri.getHost() == null
                        || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                        || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) continue;
                String path = uri.getPath();
                Set<String> visited = new HashSet<>();
                while (aliases.containsKey(path) && visited.add(path)) path = aliases.get(path);
                if (aliases.containsKey(path)) continue;
                path = path.replaceFirst("^/(?i:zh-cn|en-us)(?=/|$)", "").replaceFirst("^/amp/post/", "/post/");
                if (!path.startsWith("/post/")) continue;
                String slug = path.substring(6).replaceFirst("/$", "");
                if (!slug.isBlank() && !slug.contains("/")) result.add(slug);
                if (result.size() >= 1000) break;
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }

    static Graph traverse(String rootSlug, int depth, int maxNodes, Function<String, Source> load) {
        Source root = load.apply(rootSlug);
        if (root == null) throw new NotFoundException();
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Source> sources = new HashMap<>();
        nodes.put(root.slug(), node(root, 0));
        sources.put(root.slug(), root);
        Deque<String> pending = new ArrayDeque<>();
        pending.add(root.slug());
        Set<Edge> edges = new LinkedHashSet<>();
        boolean truncated = root.clipped();
        int examined = 0;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        outer: while (!pending.isEmpty()) {
            String slug = pending.removeFirst();
            Node from = nodes.get(slug);
            if (from.depth() >= depth) continue;
            Source source = sources.get(slug);
            truncated |= source.clipped();
            for (String targetSlug : source.targets()) {
                if (++examined > 3000 || System.nanoTime() > deadline) { truncated = true; break outer; }
                if (targetSlug.equals(slug)) continue;
                if (!nodes.containsKey(targetSlug) && nodes.size() >= maxNodes) { truncated = true; continue; }
                if (!sources.containsKey(targetSlug)) sources.put(targetSlug, load.apply(targetSlug));
                Source target = sources.get(targetSlug);
                if (target == null) continue; // Never advertise missing, private or region-hidden destinations.
                if (!nodes.containsKey(targetSlug)) {
                    if (nodes.size() >= maxNodes) { truncated = true; continue; }
                    nodes.put(targetSlug, node(target, from.depth() + 1));
                    pending.addLast(targetSlug);
                }
                edges.add(new Edge(from.id(), target.id()));
            }
        }
        return new Graph(true, root.id(), depth, List.copyOf(nodes.values()), List.copyOf(edges), truncated);
    }

    private static Node node(Source source, int depth) {
        return new Node(source.id(), source.slug(), source.title(), source.url(), depth);
    }

    private static String encode(String slug) {
        return URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
    }

    record Source(Long id, String slug, String title, String url, Set<String> targets, boolean clipped) {}
    @RegisterForReflection
    public record Node(Long id, String slug, String title, String url, int depth) {}
    @RegisterForReflection
    public record Edge(Long source, Long target) {}
    @RegisterForReflection
    public record Graph(boolean enabled, Long rootId, int depth, List<Node> nodes, List<Edge> edges, boolean truncated) {}
}
