package com.biliwind.blog.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticHtmlCacheFilterNonceTest {

    @Test
    void stripsAndReappliesNoncePlaceholderForCachedHtml() {
        String rendered = "<script nonce=\"nonce-one\">init()</script><style nonce=\"nonce-one\">body{}</style>";

        String cached = StaticHtmlCacheFilter.stripNonceForCache(rendered, "nonce-one");
        assertFalse(cached.contains("nonce-one"));
        assertTrue(cached.contains("nonce=\"" + StaticHtmlCacheFilter.NONCE_PLACEHOLDER + "\""));

        String served = StaticHtmlCacheFilter.applyNonceToCachedHtml(cached, "nonce-two");
        assertEquals("<script nonce=\"nonce-two\">init()</script><style nonce=\"nonce-two\">body{}</style>", served);
    }

    @Test
    void rewritesLegacyCachedHtmlWithoutPlaceholder() {
        String legacyCached = "<script type=\"application/ld+json\" nonce=\"oldNonce123\">{}</script>"
                + "<style nonce=\"oldNonce123\">.a{}</style>"
                + "<div data-text=\"nonce=&quot;keep&quot;\">ok</div>";

        String served = StaticHtmlCacheFilter.applyNonceToCachedHtml(legacyCached, "freshNonce456");
        assertTrue(served.contains("<script type=\"application/ld+json\" nonce=\"freshNonce456\">"));
        assertTrue(served.contains("<style nonce=\"freshNonce456\">"));
        assertTrue(served.contains("data-text=\"nonce=&quot;keep&quot;\""));
    }
}
