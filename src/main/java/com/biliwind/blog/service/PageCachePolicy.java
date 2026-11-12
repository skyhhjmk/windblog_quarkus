package com.biliwind.blog.service;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;

/**
 * The single cache policy for public pages. Anonymous documents are shared and
 * revalidated with a weak validator; reader-specific renders never reach a
 * shared cache.
 */
public final class PageCachePolicy {

    public static final String VARY = "Accept-Language, Cookie";

    private PageCachePolicy() {
    }

    public static Response.ResponseBuilder publicDocument(Response.ResponseBuilder builder, EntityTag etag) {
        return publicDocument(builder, etag, 60, 300);
    }

    public static Response.ResponseBuilder publicDocument(Response.ResponseBuilder builder, EntityTag etag,
                                                          int maxAgeSeconds, int sharedMaxAgeSeconds) {
        CacheControl cacheControl = new CacheControl();
        cacheControl.setMaxAge(maxAgeSeconds);
        cacheControl.setSMaxAge(sharedMaxAgeSeconds);
        return builder.tag(etag).cacheControl(cacheControl).header("Vary", VARY);
    }

    public static Response.ResponseBuilder privateNoStore(Response.ResponseBuilder builder) {
        return builder.header("X-WindBlog-Private", "true")
                .header("Cache-Control", "private, no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .header("Vary", VARY);
    }
}
