package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldUnwrapTransitionAddressesBeforeClassification() throws Exception {
        // NAT64 well-known prefix (64:ff9b::/96) and local-use prefix (64:ff9b:1::/48)
        assertFalsePublic("64:ff9b::7f00:1");
        assertFalsePublic("64:ff9b::a00:1");
        assertFalsePublic("64:ff9b:1:7f00:1::");
        assertFalsePublic("64:ff9b:1:c0a8:1::");
        // 6to4 (2002::/16) embeds the IPv4 address in hextets 2 and 3
        assertFalsePublic("2002:7f00:1::1");
        assertFalsePublic("2002:c0a8:1::1");
        assertFalsePublic("2002:a9fe:1::1");

        assertTrue(isPublic("64:ff9b::808:808"));
        assertTrue(isPublic("2002:808:808::1"));
        assertTrue(isPublic("2606:4700::1111"));
    }

    private boolean isPublic(String addressText) throws Exception {
        return ExternalHttpEndpointPolicy.isPublicAddress(InetAddress.getByName(addressText));
    }

    private void assertFalsePublic(String addressText) throws Exception {
        org.junit.jupiter.api.Assertions.assertFalse(
                ExternalHttpEndpointPolicy.isPublicAddress(InetAddress.getByName(addressText)),
                addressText);
    }
}
