package com.biliwind.blog.controller;

import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal deep-search endpoint must only accept the primary's own replay. */
class SearchControllerInternalTest {

    @Test
    void shouldAcceptOnlyLoopbackReplayAddresses() {
        assertTrue(SearchController.isLoopbackAddress(SocketAddress.inetSocketAddress(58080, "127.0.0.1")));
        assertTrue(SearchController.isLoopbackAddress(SocketAddress.inetSocketAddress(58080, "::1")));

        assertFalse(SearchController.isLoopbackAddress(SocketAddress.inetSocketAddress(58080, "10.0.0.100")));
        assertFalse(SearchController.isLoopbackAddress(SocketAddress.inetSocketAddress(58080, "172.23.0.1")));
        assertFalse(SearchController.isLoopbackAddress(SocketAddress.domainSocketAddress("/var/run/windblog.sock")));
        assertFalse(SearchController.isLoopbackAddress(null));
    }
}
