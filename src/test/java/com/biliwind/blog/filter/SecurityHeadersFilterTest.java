package com.biliwind.blog.filter;

import com.biliwind.blog.context.CspNonceContext;
import com.biliwind.blog.service.security.SecurityHttpSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emitsEnforcedCspAndBaselineSecurityHeadersByDefault() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.cspNonceContext = new CspNonceContext();
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerRequestContext request = requestForPath("user/login");
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        assertNull(headers.getFirst("Content-Security-Policy-Report-Only"));
        Object enforced = headers.getFirst("Content-Security-Policy");
        assertNotNull(enforced);
        String csp = enforced.toString();
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("img-src 'self' data: blob:"));
        assertTrue(csp.contains("style-src-attr 'unsafe-inline'"));
        assertFalse(csp.contains("pagead2.googlesyndication.com"));
        assertFalse(csp.contains("unsafe-eval"));

        assertEquals("DENY", headers.getFirst("X-Frame-Options"));
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("strict-origin-when-cross-origin", headers.getFirst("Referrer-Policy"));
        assertNotNull(headers.getFirst("Permissions-Policy"));
        assertTrue(headers.getFirst("Permissions-Policy").toString().contains("camera=()"));
    }

    @Test
    void emitsReportOnlyCspWhenConfiguredInDatabase() {
        ObjectNode node = MAPPER.createObjectNode()
                .put("csp_enabled", true)
                .put("csp_enforce", false)
                .put("csp_trusted_types_enabled", true)
                .put("hsts_enabled", false)
                .put("x_frame_options", "SAMEORIGIN")
                .put("x_content_type_options_nosniff", true)
                .put("referrer_policy", "no-referrer")
                .put("permissions_policy", "geolocation=()");

        SecurityHeadersFilter filter = filterWith(SecurityHttpSettings.fromNode(node));
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerRequestContext request = requestForPath("posts/hello");
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        assertNull(headers.getFirst("Content-Security-Policy"));
        String csp = headers.getFirst("Content-Security-Policy-Report-Only").toString();
        assertTrue(csp.contains("frame-ancestors 'self'"));
        assertTrue(csp.contains("require-trusted-types-for 'script'"));
        assertTrue(csp.contains("trusted-types default windblog-raw-html"));
        assertNull(headers.getFirst("Strict-Transport-Security"));
        assertEquals("SAMEORIGIN", headers.getFirst("X-Frame-Options"));
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("no-referrer", headers.getFirst("Referrer-Policy"));
        assertEquals("geolocation=()", headers.getFirst("Permissions-Policy"));
    }

    @Test
    void emitsEnforcedCspAndHstsWhenEnabled() {
        ObjectNode node = MAPPER.createObjectNode()
                .put("hsts_enabled", true)
                .put("csp_enabled", true)
                .put("csp_enforce", true)
                .put("csp_trusted_types_enabled", true);

        SecurityHeadersFilter filter = filterWith(SecurityHttpSettings.fromNode(node));
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
    void emitsBaselineHeadersAndAmpCspForAmpRequests() {
        ObjectNode node = MAPPER.createObjectNode()
                .put("csp_enabled", true)
                .put("csp_enforce", false)
                .put("x_frame_options", "DENY")
                .put("x_content_type_options_nosniff", true);

        SecurityHeadersFilter filter = filterWith(SecurityHttpSettings.fromNode(node));
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerRequestContext request = requestForPath("amp/posts/hello");
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        assertNull(headers.getFirst("Content-Security-Policy"));
        String csp = headers.getFirst("Content-Security-Policy-Report-Only").toString();
        assertTrue(csp.contains("https://cdn.ampproject.org"));
        assertEquals("DENY", headers.getFirst("X-Frame-Options"));
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
    }

    private static SecurityHeadersFilter filterWith(SecurityHttpSettings.Snapshot snapshot) {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.cspNonceContext = new CspNonceContext();
        filter.securityHttpSettings = new SecurityHttpSettings() {
            @Override
            public Snapshot current() {
                return snapshot;
            }
        };
        return filter;
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
