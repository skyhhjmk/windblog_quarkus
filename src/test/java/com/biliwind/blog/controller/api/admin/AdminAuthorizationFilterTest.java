package com.biliwind.blog.controller.api.admin;

import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AdminAuthorizationFilterTest {

    @Test
    void shouldLeaveCorsPreflightForCorsHandling() {
        ContainerRequestContext requestContext = (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, arguments) -> "getMethod".equals(method.getName()) ? "OPTIONS" : null);

        AdminAuthorizationFilter filter = new AdminAuthorizationFilter();

        assertDoesNotThrow(() -> filter.filter(requestContext));
    }
}
