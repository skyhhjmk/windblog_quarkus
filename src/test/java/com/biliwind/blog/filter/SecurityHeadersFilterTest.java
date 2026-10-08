package com.biliwind.blog.filter;

import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;

class SecurityHeadersFilterTest {
    @Test
    void emitsOnlyHstsWhenEnabled() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.hstsEnabled = true;
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(null, response);

        assertEquals("max-age=31536000; includeSubDomains", headers.getFirst("Strict-Transport-Security"));
        assertEquals(1, headers.size());
    }

    @Test
    void emitsNoSecurityHeadersWhenHstsIsDisabled() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerResponseContext response = stub(ContainerResponseContext.class, "getHeaders", headers);

        filter.filter(null, response);

        assertTrue(headers.isEmpty());
    }

    private static <T> T stub(Class<T> type, String expectedMethod, Object result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals(expectedMethod)) return result;
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
