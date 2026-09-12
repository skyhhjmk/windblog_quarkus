package com.biliwind.blog.filter;

import com.biliwind.blog.context.CspNonceContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;

class SecurityHeadersFilterTest {
    @Test
    void trustedTypesAllowsBothSanitizerPoliciesWithoutDisablingEnforcement() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.cspEnforce = true;
        filter.trustedTypesEnabled = true;
        filter.cspNonceContext = new CspNonceContext();
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        UriInfo uri = stub(UriInfo.class, "getPath", "tag");
        ContainerRequestContext request = stub(ContainerRequestContext.class, "getUriInfo", uri);
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(request, response);

        String csp = headers.getFirst("Content-Security-Policy").toString();
        assertTrue(csp.contains("require-trusted-types-for 'script'"));
        assertTrue(csp.contains("trusted-types default windblog-raw-html"));
        assertFalse(csp.contains("allow-duplicates"));
        assertFalse(csp.contains("unsafe-eval"));
    }

    private static <T> T stub(Class<T> type, String expectedMethod, Object result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals(expectedMethod)) return result;
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
