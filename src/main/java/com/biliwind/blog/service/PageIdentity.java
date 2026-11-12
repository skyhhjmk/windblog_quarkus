package com.biliwind.blog.service;

import java.util.List;

/**
 * The answer to "which page is this": the canonical URL, the languages it is
 * actually available in, the robots directive and, for articles, the AMP link.
 * Controllers resolve it once and templates consume it; nothing re-derives it
 * from the request path.
 */
public record PageIdentity(
        String canonicalUrl,
        List<SeoPageService.Alternate> alternates,
        String robots,
        String ampUrl) {
}
