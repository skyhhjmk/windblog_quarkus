package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

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
}
