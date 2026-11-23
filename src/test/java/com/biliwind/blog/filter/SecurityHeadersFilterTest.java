package com.biliwind.blog.filter;

import com.biliwind.blog.context.CspNonceContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersFilterTest {

    @Test
    void emitsReportOnlyCspWithTrustedTypesByDefault() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.cspEnforce = false;
        filter.trustedTypesEnabled = true;
        filter.cspNonceContext = new CspNonceContext();
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerRequestContext request = requestForPath("posts/hello");
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        assertNull(headers.getFirst("Content-Security-Policy"));
        Object reportOnly = headers.getFirst("Content-Security-Policy-Report-Only");
        assertNotNull(reportOnly);
        String csp = reportOnly.toString();
        assertTrue(csp.contains("img-src 'self' data: blob:"));
        assertTrue(csp.contains("style-src-attr 'unsafe-inline'"));
        assertTrue(csp.contains("require-trusted-types-for 'script'"));
        assertTrue(csp.contains("trusted-types default windblog-raw-html"));
        assertFalse(csp.contains("pagead2.googlesyndication.com"));
        assertFalse(csp.contains("allow-duplicates"));
        assertFalse(csp.contains("unsafe-eval"));
        assertNull(headers.getFirst("Strict-Transport-Security"));
    }

    @Test
    void emitsEnforcedCspAndHstsWhenEnabled() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.hstsEnabled = true;
        filter.cspEnforce = true;
        filter.trustedTypesEnabled = true;
        filter.cspNonceContext = new CspNonceContext();
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerRequestContext request = requestForPath("tag");
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        assertNull(headers.getFirst("Content-Security-Policy-Report-Only"));
        String csp = headers.getFirst("Content-Security-Policy").toString();
        assertTrue(csp.contains("require-trusted-types-for 'script'"));
        assertTrue(csp.contains("trusted-types default windblog-raw-html"));
        assertEquals("max-age=31536000; includeSubDomains", headers.getFirst("Strict-Transport-Security"));
    }

    @Test
    void emitsReportOnlyCspForAmpWhenNotEnforced() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.cspEnforce = false;
        filter.cspNonceContext = new CspNonceContext();
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerRequestContext request = requestForPath("amp/posts/hello");
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        assertNull(headers.getFirst("Content-Security-Policy"));
        String csp = headers.getFirst("Content-Security-Policy-Report-Only").toString();
        assertTrue(csp.contains("https://cdn.ampproject.org"));
    }

    private static ContainerRequestContext requestForPath(String path) {
        UriInfo uri = stub(UriInfo.class, "getPath", path);
        return stub(ContainerRequestContext.class, "getUriInfo", uri);
    }

    private static <T> T stub(Class<T> type, String expectedMethod, Object result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals(expectedMethod)) {
                        return result;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
