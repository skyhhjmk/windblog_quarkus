package com.biliwind.blog.service.settings;

/**
 * How a setting stores per-region values. The storage shapes genuinely differ,
 * so the catalogue names the shape and {@link RegionalSettings} owns the single
 * implementation for each kind.
 */
public enum RegionalKind {
    /** No regional semantics; the root value is authoritative. */
    NONE,
    /** {@code regions.<code>} is an object overlaid on the root object. */
    OBJECT_REGIONS,
    /** {@code regions.<code>} is a language tag or {@code {default_language}}. */
    LANGUAGE,
    /** {@code favicon_url_regions.<code>}, then {@code regions.<code>.favicon_url}. */
    FAVICON,
    /** {@code site_url_regions.<code>} then the root {@code site_url}. */
    SITE_URL
}
