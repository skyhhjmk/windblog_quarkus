package com.biliwind.blog.filter;

import com.biliwind.blog.service.PageCachePolicy;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
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

    @Test
    void publicDocumentPolicyDisablesSharedCdnCaching() {
        Response response = PageCachePolicy.publicDocument(
                Response.ok("<html></html>"), new EntityTag("v1", true)).build();
        assertEquals(PageCachePolicy.HTML_NO_STORE, response.getHeaderString(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-cache", response.getHeaderString("Pragma"));
        assertEquals("0", response.getHeaderString("Expires"));
        assertEquals(PageCachePolicy.PUBLIC_VARY, response.getHeaderString(HttpHeaders.VARY));
    }
}
