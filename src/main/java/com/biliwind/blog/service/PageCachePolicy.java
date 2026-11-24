package com.biliwind.blog.service;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;

/**
 * The single cache policy for public pages. HTML documents carry per-request
 * CSP nonces, so upstream CDNs and browsers must not store or 304-revalidate
 * them; origin-side disk static HTML caching handles fast serving instead.
 */
public final class PageCachePolicy {

    public static final String VARY = "Accept-Language, Cookie";
    public static final String PUBLIC_VARY = "Accept-Language, X-PJAX";
    public static final String HTML_NO_STORE = "no-cache, no-store, must-revalidate";

    private PageCachePolicy() {
    }

    public static Response.ResponseBuilder publicDocument(Response.ResponseBuilder builder, EntityTag etag) {
        return publicDocument(builder, etag, 0, 0);
    }

    public static Response.ResponseBuilder publicDocument(Response.ResponseBuilder builder, EntityTag etag,
                                                          int maxAgeSeconds, int sharedMaxAgeSeconds) {
        return builder.tag(etag)
                .header("Cache-Control", HTML_NO_STORE)
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .header("Vary", PUBLIC_VARY);
    }

    public static Response.ResponseBuilder privateNoStore(Response.ResponseBuilder builder) {
        return builder.header("X-WindBlog-Private", "true")
                .header("Cache-Control", "private, no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .header("Vary", VARY);
    }
}
