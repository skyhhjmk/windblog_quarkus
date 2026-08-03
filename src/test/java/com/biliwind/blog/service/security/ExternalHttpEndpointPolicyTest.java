package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
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

    @Test
    void shouldRejectSpecialIpv6Ranges() throws Exception {
        assertFalsePublic("fc00::1");
        assertFalsePublic("fe80::1");
        assertFalsePublic("ff02::1");
        assertFalsePublic("2001:db8::1");
    }

    private void assertFalsePublic(String addressText) throws Exception {
        org.junit.jupiter.api.Assertions.assertFalse(
                ExternalHttpEndpointPolicy.isPublicAddress(InetAddress.getByName(addressText)),
                addressText);
    }
}
