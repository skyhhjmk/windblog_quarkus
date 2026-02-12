package com.biliwind.blog.controller.api.admin;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class AdminJwtAuthFilter implements ContainerRequestFilter {

    public static final String REQUEST_USER_ID_KEY = "admin.user.id";
    public static final String REQUEST_USERNAME_KEY = "admin.user.name";

    @Inject
    AdminTokenVerifier tokenVerifier;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = normalizePath(requestContext.getUriInfo().getPath());
        if (!path.startsWith("/api/admin")) {
            return;
        }
        if (path.equals("/api/admin/auth/login")
                || path.equals("/api/admin/auth/me")
                || path.startsWith("/api/admin/docs")
                || path.startsWith("/api/admin/openapi")) {
            return;
        }

        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "缺少 Bearer Token");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "Token 不能为空");
            return;
        }

        AdminTokenVerifier.VerifiedToken verified = tokenVerifier.verify(token);
        if (verified == null) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "Token 校验失败");
            return;
        }
        if (!verified.isAdmin()) {
            abort(requestContext, Response.Status.FORBIDDEN, "没有管理员权限");
            return;
        }
        requestContext.setProperty(REQUEST_USER_ID_KEY, verified.uid());
        requestContext.setProperty(REQUEST_USERNAME_KEY, verified.username());
    }

    private void abort(ContainerRequestContext requestContext, Response.Status status, String message) {
        requestContext.abortWith(Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("success", false, "message", message))
                .build());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
    }
}
