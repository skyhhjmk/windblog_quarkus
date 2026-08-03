package com.biliwind.blog.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

/** Keeps every protected content response out of shared caches, including errors and redirects. */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class ProtectedContentCacheFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null) {
            return;
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (normalizedPath.startsWith("/api/user/post/")) {
            applyNoStore(responseContext.getHeaders());
        }
    }

    static void applyNoStore(MultivaluedMap<String, Object> headers) {
        if (headers == null) {
            return;
        }
        headers.putSingle("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.putSingle("Pragma", "no-cache");
        headers.putSingle("Expires", "0");
        headers.putSingle("Vary", "Cookie");
    }
}
