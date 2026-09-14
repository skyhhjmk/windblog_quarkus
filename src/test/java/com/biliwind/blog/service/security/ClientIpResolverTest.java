package com.biliwind.blog.service.security;

import com.biliwind.blog.service.ConfigManager;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

    @Test
    void shouldRegisterIpResolutionForNativeJsonSerialization() {
        assertTrue(ClientIpResolver.ClientIpResolution.class
                .isAnnotationPresent(RegisterForReflection.class));
    }

    @Test
    void shouldAcceptOnlyIpv4AndIpv6Literals() {
        assertTrue(ClientIpResolver.isAddressLiteral("203.0.113.10"));
        assertTrue(ClientIpResolver.isAddressLiteral("2001:db8::10"));
        assertTrue(ClientIpResolver.isAddressLiteral("::ffff:192.0.2.10"));
        assertTrue(ClientIpResolver.isAddressLiteral("[2001:db8::10]"));

        assertFalse(ClientIpResolver.isAddressLiteral("proxy.example.com"));
        assertFalse(ClientIpResolver.isAddressLiteral("203.0.113"));
        assertFalse(ClientIpResolver.isAddressLiteral("203.0.113.999"));
        assertFalse(ClientIpResolver.isAddressLiteral("2001:db8::10%eth0"));
    }

    @Test
    void shouldHandleDirectHttpIpAccessWithoutProxyHeader() {
        ClientIpResolver resolver = new ClientIpResolver();
        resolver.configManager = new ConfigManager() {
            @Override
            public String getString(String key, String field, String defaultValue) {
                return defaultValue;
            }
        };

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("10.0.0.100", null);

        assertEquals("10.0.0.100", resolution.clientIp());
        assertTrue(resolution.trustedProxy());
        assertEquals("10.0.0.100", resolution.remoteIp());
    }
}
