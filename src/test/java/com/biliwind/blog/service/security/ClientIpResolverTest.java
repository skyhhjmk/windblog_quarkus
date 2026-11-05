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

    @Test
    void shouldUseForwardedHeaderForTrustedProxyInAutoMode() {
        ClientIpResolver resolver = resolverWithSource("auto");

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("10.0.0.100", "203.0.113.5, 10.0.0.1");

        assertEquals("203.0.113.5", resolution.clientIp());
        assertEquals("10.0.0.100", resolution.remoteIp());
        assertTrue(resolution.trustedProxy());
    }

    @Test
    void shouldUseNetworkAddressWhenConfiguredForNetworkSource() {
        ClientIpResolver resolver = resolverWithSource("network");

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("10.0.0.100", "203.0.113.5");

        assertEquals("10.0.0.100", resolution.clientIp());
        assertEquals("10.0.0.100", resolution.remoteIp());
    }

    @Test
    void shouldUseForwardedHeaderWhenConfiguredForHeaderSource() {
        ClientIpResolver resolver = resolverWithSource("header");

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("198.51.100.20", "203.0.113.5");

        assertEquals("203.0.113.5", resolution.clientIp());
        assertEquals("198.51.100.20", resolution.remoteIp());
        assertFalse(resolution.trustedProxy());
    }

    @Test
    void shouldFallBackToNetworkAddressWhenHeaderSourceHasNoValidAddress() {
        ClientIpResolver resolver = resolverWithSource("header");

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("198.51.100.20", "not-an-ip");

        assertEquals("198.51.100.20", resolution.clientIp());
        assertEquals("198.51.100.20", resolution.remoteIp());
    }

    @Test
    void shouldFallBackToAutoForUnknownSource() {
        ClientIpResolver resolver = resolverWithSource("unsupported");

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("10.0.0.100", "203.0.113.5");

        assertEquals("203.0.113.5", resolution.clientIp());
    }

    @Test
    void shouldIgnoreClientSuppliedLeftmostForwardedForEntries() {
        ClientIpResolver resolver = resolverWithSource("auto");

        // Client sent "X-Forwarded-For: 1.2.3.4"; nginx appended the real peer.
        ClientIpResolver.ClientIpResolution resolution = resolver.resolve("172.18.0.5", "1.2.3.4, 198.51.100.7");

        assertEquals("198.51.100.7", resolution.clientIp());
    }

    @Test
    void shouldSkipTrustedHopsFromTheRight() {
        ClientIpResolver resolver = resolverWithSource("auto");

        ClientIpResolver.ClientIpResolution resolution = resolver.resolve(
                "10.0.0.100", "6.6.6.6, 198.51.100.7, 10.0.0.2, 192.168.1.1");

        assertEquals("198.51.100.7", resolution.clientIp());
    }

    @Test
    void shouldUseLeftmostAddressWhenEveryHopIsTrusted() {
        ClientIpResolver resolver = resolverWithSource("auto");

        assertEquals("192.168.1.20", resolver.resolve("10.0.0.100", "192.168.1.20, 10.0.0.2").clientIp());
    }

    @Test
    void shouldStopAtGarbageBecauseEntriesLeftOfItAreUntrusted() {
        ClientIpResolver resolver = resolverWithSource("auto");

        assertEquals("10.0.0.2", resolver.resolve("10.0.0.100", "6.6.6.6, unknown, 10.0.0.2").clientIp());
        assertEquals("10.0.0.100", resolver.resolve("10.0.0.100", "unknown").clientIp());
    }

    @Test
    void shouldApplyRightToLeftRuleInHeaderMode() {
        ClientIpResolver resolver = resolverWithSource("header");

        assertEquals("198.51.100.7", resolver.resolve("198.51.100.20", "1.2.3.4, 198.51.100.7").clientIp());
    }

    @Test
    void shouldReadCustomSingleValueHeaderWithPortsAndBrackets() {
        ClientIpResolver resolver = resolver("auto", "EO-Connecting-IP");

        assertEquals("EO-Connecting-IP", resolver.resolveHeaderName());
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.100", "203.0.113.9:51234").clientIp());
        assertEquals("2001:db8::9", resolver.resolve("10.0.0.100", "[2001:db8::9]:443").clientIp());
    }

    @Test
    void shouldParseRfc7239ForwardedHeader() {
        ClientIpResolver resolver = resolver("auto", "Forwarded");

        assertEquals("198.51.100.7", resolver.resolve("10.0.0.100",
                "for=1.2.3.4, for=\"198.51.100.7:8443\";proto=https, for=10.0.0.2;by=10.0.0.100").clientIp());
    }

    @Test
    void shouldFallBackToDefaultHeaderForInvalidCustomHeaderName() {
        assertEquals("X-Forwarded-For", resolver("auto", "Bad Header\r\nX").resolveHeaderName());
        assertEquals("X-Forwarded-For", resolver("auto", "  ").resolveHeaderName());
        assertEquals("CF-Connecting-IP", resolver("auto", " CF-Connecting-IP ").resolveHeaderName());
    }

    private static ClientIpResolver resolver(String source, String header) {
        ClientIpResolver resolver = new ClientIpResolver();
        resolver.configManager = new ConfigManager() {
            @Override
            public String getString(String key, String field, String defaultValue) {
                if ("client_ip_source".equals(field)) {
                    return source;
                }
                if ("client_ip_header".equals(field)) {
                    return header;
                }
                return defaultValue;
            }
        };
        return resolver;
    }

    private static ClientIpResolver resolverWithSource(String source) {
        ClientIpResolver resolver = new ClientIpResolver();
        resolver.configManager = new ConfigManager() {
            @Override
            public String getString(String key, String field, String defaultValue) {
                if ("client_ip_source".equals(field)) {
                    return source;
                }
                return defaultValue;
            }
        };
        return resolver;
    }
}
