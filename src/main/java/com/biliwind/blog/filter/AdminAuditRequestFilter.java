package com.biliwind.blog.filter;

import com.biliwind.blog.context.AdminAuditRequestContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION - 10)
@ApplicationScoped
public class AdminAuditRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String REQUEST_ID_HEADER_NAME = "X-Request-Id";
    private static final String REQUEST_ID_PROPERTY = "admin.audit.request.id";

    @Inject
    AdminAuditRequestContext adminAuditRequestContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String requestPath = normalizePath(requestContext.getUriInfo().getPath());
        if (!requestPath.startsWith("/api/admin")) {
            return;
        }

        String requestId = resolveHeaderValue(requestContext, REQUEST_ID_HEADER_NAME);
        if (isBlank(requestId)) {
            requestId = UUID.randomUUID().toString();
        }

        String requestMethod = requestContext.getMethod();
        String clientIp = resolveClientIp(requestContext);
        String userAgent = resolveHeaderValue(requestContext, "User-Agent");

        adminAuditRequestContext.setRequestId(requestId);
        adminAuditRequestContext.setRequestMethod(requestMethod);
        adminAuditRequestContext.setRequestPath(requestPath);
        adminAuditRequestContext.setClientIp(clientIp);
        adminAuditRequestContext.setUserAgent(userAgent);

        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String requestPath = normalizePath(requestContext.getUriInfo().getPath());
        if (!requestPath.startsWith("/api/admin")) {
            return;
        }

        Object requestId = requestContext.getProperty(REQUEST_ID_PROPERTY);
        if (requestId != null && !isBlank(requestId.toString())) {
            responseContext.getHeaders().putSingle(REQUEST_ID_HEADER_NAME, requestId.toString());
        }
    }

    private String resolveClientIp(ContainerRequestContext requestContext) {
        String clientIp = resolveHeaderValue(requestContext, "X-Forwarded-For");
        if (!isBlank(clientIp)) {
            int commaIndex = clientIp.indexOf(',');
            if (commaIndex > -1) {
                clientIp = clientIp.substring(0, commaIndex);
            }
            return clientIp.trim();
        }

        clientIp = resolveHeaderValue(requestContext, "X-Real-IP");
        if (!isBlank(clientIp)) {
            return clientIp.trim();
        }

        clientIp = resolveHeaderValue(requestContext, "CF-Connecting-IP");
        if (!isBlank(clientIp)) {
            return clientIp.trim();
        }

        clientIp = resolveHeaderValue(requestContext, "X-Client-IP");
        if (!isBlank(clientIp)) {
            return clientIp.trim();
        }

        return "unknown";
    }

    private String resolveHeaderValue(ContainerRequestContext requestContext, String headerName) {
        String headerValue = requestContext.getHeaderString(headerName);
        if (!isBlank(headerValue)) {
            return headerValue;
        }

        List<String> headerValues = requestContext.getHeaders().get(headerName);
        if (headerValues != null && !headerValues.isEmpty()) {
            String firstHeaderValue = headerValues.get(0);
            if (!isBlank(firstHeaderValue)) {
                return firstHeaderValue;
            }
        }

        return null;
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
