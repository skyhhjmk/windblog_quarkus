package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.Tag;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background full-site static HTML cache warm-up service.
 * Enumerates all public cacheable routes and pre-generates both full HTML
 * documents and PJAX fragments on disk without waiting for visitor traffic.
 */
@ApplicationScoped
public class StaticHtmlWarmupService {

    private static final Logger LOG = Logger.getLogger(StaticHtmlWarmupService.class);
    private static final int INDEX_PAGE_SIZE = 10;
    private static final int MAX_INDEX_PAGES = 100;

    private static final List<String> STATIC_ROUTES = List.of(
            "/",
            "/?mode=standard",
            "/?mode=tutorial",
            "/?mode=resource",
            "/categories",
            "/tags",
            "/link",
            "/tools",
            "/tools/subnet",
            "/tools/timestamp",
            "/tools/log-formatter",
            "/tools/base-encoder",
            "/tools/cron",
            "/tools/regex",
            "/tools/hash",
            "/tools/json-diff",
            "/tools/serial",
            "/tools/file-transfer",
            "/tools/image-generator",
            "/tools/image-converter",
            "/tools/ai-token",
            "/tools/jwt",
            "/tools/config-converter",
            "/tools/curl-converter",
            "/tools/id-generator",
            "/tools/jsonl-to-table",
            "/terms",
            "/privacy"
    );

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @Inject
    StaticHtmlCacheService staticHtmlCacheService;

    @Inject
    ManagedExecutor managedExecutor;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger succeededTasks = new AtomicInteger(0);
    private final AtomicInteger failedTasks = new AtomicInteger(0);

    private volatile String currentTarget = "";
    private volatile String startedAt = null;
    private volatile String finishedAt = null;
    private volatile String lastError = null;
    private volatile boolean includePjax = true;

    public WarmupStatus startWarmup(boolean withPjax) {
        if (!staticHtmlCacheService.isEnabled()) {
            throw new IllegalStateException("全站静态缓存当前处于暂停状态，请先开启后再预生成");
        }
        if (!running.compareAndSet(false, true)) {
            return getStatus();
        }

        cancelRequested.set(false);
        totalTasks.set(0);
        completedTasks.set(0);
        succeededTasks.set(0);
        failedTasks.set(0);
        currentTarget = "";
        startedAt = OffsetDateTime.now().toString();
        finishedAt = null;
        lastError = null;
        this.includePjax = withPjax;

        List<WarmupTarget> targets = buildTargets(withPjax);
        totalTasks.set(targets.size());

        managedExecutor.execute(() -> runWarmupLoop(targets));
        return getStatus();
    }

    public WarmupStatus cancelWarmup() {
        if (running.get()) {
            cancelRequested.set(true);
        }
        return getStatus();
    }

    public WarmupStatus getStatus() {
        return new WarmupStatus(
                running.get(),
                cancelRequested.get(),
                includePjax,
                totalTasks.get(),
                completedTasks.get(),
                succeededTasks.get(),
                failedTasks.get(),
                currentTarget,
                startedAt,
                finishedAt,
                lastError
        );
    }

    private List<WarmupTarget> buildTargets(boolean withPjax) {
        Set<String> routes = new LinkedHashSet<>(STATIC_ROUTES);

        try {
            long postCount = Post.count("status = ?1 and deletedAt is null and visibility = 0", PostStatus.PUBLISHED);
            long totalPages = Math.min(MAX_INDEX_PAGES, Math.max(1L, (postCount + INDEX_PAGE_SIZE - 1) / INDEX_PAGE_SIZE));
            for (int page = 2; page <= totalPages; page++) {
                routes.add("/page/" + page);
            }

            List<Post> posts = Post.find("status = ?1 and deletedAt is null and visibility = 0 order by publishedAt desc",
                    PostStatus.PUBLISHED).list();
            for (Post post : posts) {
                if (post.slug != null && !post.slug.isBlank()) {
                    routes.add("/post/" + post.slug.trim());
                }
            }

            List<Category> categories = Category.listAll();
            for (Category category : categories) {
                if (category.slug != null && !category.slug.isBlank()) {
                    routes.add("/category/" + category.slug.trim());
                }
            }

            List<Tag> tags = Tag.listAll();
            for (Tag tag : tags) {
                if (tag.slug != null && !tag.slug.isBlank()) {
                    routes.add("/tag/" + tag.slug.trim());
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to enumerate dynamic routes for warmup: %s", e.getMessage());
            lastError = e.getMessage();
        }

        List<WarmupTarget> targets = new ArrayList<>();
        for (String lang : LanguageConstant.SUPPORT_LANGS) {
            for (String route : routes) {
                String urlPath = "/" + lang + (route.equals("/") ? "" : route);
                targets.add(new WarmupTarget(urlPath, false));
                if (withPjax) {
                    targets.add(new WarmupTarget(urlPath, true));
                }
            }
        }
        return targets;
    }

    private void runWarmupLoop(List<WarmupTarget> targets) {
        String baseUrl = "http://127.0.0.1:" + httpPort;
        try {
            for (WarmupTarget target : targets) {
                if (cancelRequested.get()) {
                    break;
                }
                currentTarget = (target.pjax() ? "[PJAX] " : "[HTML] ") + target.urlPath();
                try {
                    HttpRequest.Builder req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + target.urlPath()))
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "text/html")
                            .GET();
                    if (target.pjax()) {
                        req.header(PjaxHelper.PJAX_HEADER, "true");
                        req.header(PjaxHelper.PJAX_CONTAINER_HEADER, PjaxHelper.PJAX_CONTAINER_DEFAULT);
                    }
                    HttpResponse<Void> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        succeededTasks.incrementAndGet();
                    } else {
                        failedTasks.incrementAndGet();
                    }
                } catch (Exception ex) {
                    failedTasks.incrementAndGet();
                    lastError = ex.getMessage();
                } finally {
                    completedTasks.incrementAndGet();
                }
            }
        } finally {
            currentTarget = "";
            finishedAt = OffsetDateTime.now().toString();
            running.set(false);
        }
    }

    private record WarmupTarget(String urlPath, boolean pjax) {}

    @RegisterForReflection
    public record WarmupStatus(
            boolean running,
            boolean cancelRequested,
            boolean includePjax,
            int totalTasks,
            int completedTasks,
            int succeededTasks,
            int failedTasks,
            String currentTarget,
            String startedAt,
            String finishedAt,
            String lastError
    ) {}
}
