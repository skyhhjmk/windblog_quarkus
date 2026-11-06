package com.biliwind.blog.filter;

import com.biliwind.blog.controller.api.admin.AdminAuthorizationPolicy;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Map;
import java.util.Optional;

/**
 * Refuses admin requests whose raw path is ambiguous before JAX-RS routing.
 *
 * <p>Quarkus normalises the request URI for {@code UriInfo}, so by the time a
 * {@code ContainerRequestFilter} runs any encoded dot segment has already been
 * resolved and the original spelling is gone. This pre-matching filter reads
 * the untouched Vert.x path instead, so {@code /api/admin/x/%2e%2e/...} and
 * {@code /api/admin/...%2f...} are rejected with 400 instead of being routed.
 */
@ApplicationScoped
public class AdminPathGuardFilter {

    @Inject
    RoutingContext routingContext;

    @ServerRequestFilter(preMatching = true, priority = Priorities.AUTHENTICATION - 60)
    public Optional<Response> rejectAmbiguousAdminPath(ContainerRequestContext request) {
        String rawPath = routingContext.request() == null ? null : routingContext.request().path();
        if (rawPath == null || rawPath.isBlank()) {
            rawPath = request.getUriInfo().getRequestUri().getRawPath();
        }
        if (!AdminAuthorizationPolicy.rejectsAmbiguousAdminPath(rawPath)) {
            return Optional.empty();
        }
        return Optional.of(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("success", false, "code", "ADMIN_PATH_INVALID",
                        "message", "管理端请求路径不合法"))
                .build());
    }
}
