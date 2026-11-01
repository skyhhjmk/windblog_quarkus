package com.biliwind.blog.filter;

import com.biliwind.blog.service.SeoManagementService;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

@ApplicationScoped
public class SeoResponseFilter {
    @Inject SeoManagementService seo;
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(SeoResponseFilter.class);

    @ServerResponseFilter
    public Uni<Void> filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getRequestUri().getPath();
        if (path.startsWith("/api/")) return Uni.createFrom().voidItem();
        if (path.startsWith("/q/")) {
            response.getHeaders().putSingle("X-Robots-Tag", "noindex");
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem().invoke(() -> {
            if (response.getStatus() == 404 && "GET".equals(request.getMethod())) {
                try { seo.recordNotFound(path, request.getHeaderString("Referer")); }
                catch (RuntimeException e) { LOG.debug("SEO 404 recording failed", e); }
            }
            if (isCrawlDocument(path) && "GET".equals(request.getMethod()) && response.getStatus() < 400) {
                // Sitemaps, robots and feeds are stable between publishes; let
                // crawlers and the edge cache reuse them instead of rebuilding
                // (and re-querying) on every request.
                response.getHeaders().putSingle("Cache-Control", "public, max-age=3600");
                if (path.equals("/feed") || path.equals("/rss.xml")) {
                    // The feed language follows the user_lang_v2 cookie.
                    response.getHeaders().putSingle("Vary", "Cookie");
                }
            }
            if (response.getMediaType() == null || response.getMediaType().isCompatible(jakarta.ws.rs.core.MediaType.TEXT_HTML_TYPE)) {
                String robots = response.getStatus() >= 400 ? "noindex,follow" : seo.robots(path);
                response.getHeaders().putSingle("X-Robots-Tag", robots);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private static boolean isCrawlDocument(String path) {
        return path.equals("/robots.txt") || path.equals("/feed") || path.equals("/rss.xml")
                || path.startsWith("/sitemap");
    }
}
