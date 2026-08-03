package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalHttpEndpointPolicyTest {

    @Test
    void shouldRejectUnsupportedOrCredentialBearingEndpoints() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalHttpEndpointPolicy.validateHttpUri(
                        URI.create("file:///etc/passwd"), "AI endpoint"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalHttpEndpointPolicy.validateHttpUri(
                        URI.create("https://user:password@example.com/api"), "AI endpoint"));
    }

    @Test
    void shouldClassifyPrivateAndMappedAddressesWithOnePolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalHttpEndpointPolicy.requirePublicAddresses("127.0.0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalHttpEndpointPolicy.requirePublicAddresses("::ffff:127.0.0.1"));
        assertDoesNotThrow(() -> ExternalHttpEndpointPolicy.resolveAddresses("127.0.0.1"));
    }
}
