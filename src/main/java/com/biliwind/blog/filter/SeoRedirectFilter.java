package com.biliwind.blog.filter;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.SeoRedirect;
import com.biliwind.blog.service.SeoManagementService;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

@ApplicationScoped
public class SeoRedirectFilter {
    @Inject SeoManagementService seo;

    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return Uni.createFrom().nullItem();
        String path = request.getUriInfo().getRequestUri().getPath();
        if (path.startsWith("/api/") || path.startsWith("/q/") || path.startsWith("/assets/")
                || path.startsWith("/uploads/") || path.equals("/sw.js")
                || path.equals("/manifest.webmanifest")) return Uni.createFrom().nullItem();
        return Uni.createFrom().item(() -> {
            SeoRedirect rule = SeoRedirect.find("source = ?1 and enabled = true", path).firstResult();
            if (rule == null) return (Response) null;
            String target = seo.redirectTarget(path);
            if (target == null || target.equals(path)) return (Response) null;
            target = preserveLanguage(path, target, request);
            return Response.status(rule.status).location(java.net.URI.create(target))
                    .header("Cache-Control", "no-store").build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * The language route filter strips the prefix before RESTEasy sees the
     * request. Re-apply it to article redirect targets so an old English URL
     * lands on the English article instead of the region default.
     */
    private String preserveLanguage(String source, String target, ContainerRequestContext request) {
        if (!(source.startsWith("/post/") || source.startsWith("/amp/post/"))) {
            return target;
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        String lang = request.getHeaderString(LanguageRouteFilter.LANG_HEADER);
        String defaultLang = request.getHeaderString(LanguageRouteFilter.DEFAULT_LANG_HEADER);
        if (lang == null || defaultLang == null) {
            return target;
        }
        return LanguageHelper.localizedPath(LanguageHelper.stripLanguagePrefix(target), lang, defaultLang);
    }
}
