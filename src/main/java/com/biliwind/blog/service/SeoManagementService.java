package com.biliwind.blog.service;

import com.biliwind.blog.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import java.net.URI;
import java.util.*;

@ApplicationScoped
public class SeoManagementService {
    @Inject EntityManager em;
    @Inject ConfigManager config;

    public String robots(String path) {
        boolean noindex = !config.getBoolean("seo", "index_enabled", true);
        boolean nofollow = !config.getBoolean("seo", "follow_enabled", true);
        if (path.matches("(?i)(/zh-cn|/en-us)?/search(/.*)?") || path.startsWith("/admin")
                || path.startsWith("/api/")) noindex = true;
        String plain = path.replaceFirst("^/(?i:zh-cn|en-us)(?=/|$)", "").replace("/amp/post/", "/post/");
        if (plain.startsWith("/post/")) {
            String slug = java.net.URLDecoder.decode(plain.substring(6), java.nio.charset.StandardCharsets.UTF_8);
            Post post = Post.find("slug", slug).firstResult();
            if (post != null) {
                SeoPostPolicy policy = SeoPostPolicy.find("postId", post.id).firstResult();
                noindex |= post.visibility != 0 || post.status != PostStatus.PUBLISHED;
                if (policy != null) { noindex |= policy.noindex; nofollow |= policy.nofollow; }
            }
        }
        return (noindex ? "noindex" : "index") + "," + (nofollow ? "nofollow" : "follow");
    }

    public String validatePath(String path) {
        if (path == null || path.length() > 512 || !path.startsWith("/") || path.startsWith("//")
                || path.contains("\\") || path.contains("?") || path.contains("#") || path.contains("%")
                || path.chars().anyMatch(c -> c <= 32 || c == 127)) throw new BadRequestException("请输入站内绝对路径，不含查询参数、片段或转义字符");
        URI uri;
        try { uri = URI.create(path); } catch (IllegalArgumentException e) { throw new BadRequestException("路径格式无效"); }
        if (!uri.normalize().getPath().equals(path) || path.startsWith("/api") || path.startsWith("/admin")
                || path.startsWith("/q/") || path.startsWith("/assets/") || path.startsWith("/uploads/")
                || path.startsWith("/sitemap") || path.equals("/robots.txt")) throw new BadRequestException("不能重定向系统路径或包含点段的路径");
        return path;
    }

    @Transactional
    public SeoRedirect saveRedirect(Long id, String source, String target, int status, boolean enabled) {
        source = validatePath(source); target = validatePath(target);
        if (!Set.of(301, 302, 307, 308).contains(status)) throw new BadRequestException("不支持的重定向状态码");
        if (source.equals(target)) throw new BadRequestException("不能重定向到自身");
        // Serialize graph edits across nodes so concurrent saves cannot introduce a cycle.
        em.createNativeQuery("select pg_advisory_xact_lock(145001)").getSingleResult();
        SeoRedirect duplicate = SeoRedirect.find("source", source).firstResult();
        if (duplicate != null && !Objects.equals(duplicate.id, id)) throw new BadRequestException("来源路径已存在");
        Map<String, String> edges = new HashMap<>();
        for (SeoRedirect rule : SeoRedirect.<SeoRedirect>list("enabled = true")) {
            if (!Objects.equals(rule.id, id)) edges.put(rule.source, rule.target);
        }
        if (enabled) edges.put(source, target);
        for (String start : edges.keySet()) {
            Set<String> seen = new HashSet<>();
            for (String current = start; current != null; current = edges.get(current)) {
                if (!seen.add(current)) throw new BadRequestException("重定向规则存在循环");
            }
        }
        SeoRedirect rule = id == null ? new SeoRedirect() : SeoRedirect.findById(id);
        if (rule == null) throw new jakarta.ws.rs.NotFoundException();
        rule.source = source; rule.target = target; rule.status = status; rule.enabled = enabled;
        rule.persist();
        return rule;
    }

    @Transactional
    public void recordPostMove(String oldSlug, String newSlug) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add("");
        com.biliwind.blog.common.constant.LanguageConstant.SUPPORT_LANGS.stream().sorted()
                .forEach(lang -> prefixes.add("/" + lang));
        for (String prefix : prefixes) {
            for (String route : List.of("/post/", "/amp/post/")) {
                String source = prefix + route + oldSlug;
                String target = prefix + route + newSlug;
                SeoRedirect.delete("source", target);
                SeoRedirect existing = SeoRedirect.find("source", source).firstResult();
                saveRedirect(existing == null ? null : existing.id, source, target, 301, true);
            }
        }
    }

    public String redirectTarget(String path) {
        Set<String> seen = new HashSet<>();
        String current = path;
        while (seen.add(current)) {
            SeoRedirect rule = SeoRedirect.find("source = ?1 and enabled = true", current).firstResult();
            if (rule == null) return current;
            current = rule.target;
        }
        return null;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordNotFound(String path, String referer) {
        if (path == null || path.startsWith("/api/") || path.startsWith("/q/")) return;
        path = path.substring(0, Math.min(path.length(), 512));
        // Keep only origin and path, never credentials, query strings or fragments.
        String safeReferer = "";
        try {
            URI ref = URI.create(referer == null ? "" : referer);
            if (ref.getHost() != null && Set.of("http", "https").contains(ref.getScheme()))
                safeReferer = new URI(ref.getScheme(), null, ref.getHost(), ref.getPort(), ref.getPath(), null, null).toASCIIString();
        } catch (Exception ignored) { }
        if (safeReferer.length() > 512) safeReferer = safeReferer.substring(0, 512);
        em.createNativeQuery("insert into seo_not_found(path, referer) values (?1, ?2) on conflict(path) do update "
                + "set hits = seo_not_found.hits + 1, referer = excluded.referer, last_seen = current_timestamp, resolved = false")
                .setParameter(1, path).setParameter(2, safeReferer).executeUpdate();
    }

    @io.quarkus.scheduler.Scheduled(every = "24h", concurrentExecution = io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void pruneNotFound() {
        em.createNativeQuery("delete from seo_not_found where last_seen < current_timestamp - interval '90 days'").executeUpdate();
        em.createNativeQuery("delete from seo_not_found where path in (select path from seo_not_found order by last_seen desc offset 10000)").executeUpdate();
    }
}
