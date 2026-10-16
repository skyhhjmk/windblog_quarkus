package com.biliwind.blog.filter;

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
                || path.startsWith("/uploads/")) return Uni.createFrom().nullItem();
        return Uni.createFrom().item(() -> {
            SeoRedirect rule = SeoRedirect.find("source = ?1 and enabled = true", path).firstResult();
            if (rule == null) return (Response) null;
            String target = seo.redirectTarget(path);
            if (target == null || target.equals(path)) return (Response) null;
            return Response.status(rule.status).location(java.net.URI.create(target))
                    .header("Cache-Control", "no-store").build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
