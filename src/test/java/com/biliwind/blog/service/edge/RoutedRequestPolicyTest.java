package com.biliwind.blog.service.edge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutedRequestPolicyTest {

    @Test
    void shouldAllowEdgeWriteShapes() {
        assertNull(RoutedRequestPolicy.rejectionReason("POST", "/api/comments", null));
        assertNull(RoutedRequestPolicy.rejectionReason("post", "/api/user/post/password/12", ""));
        assertNull(RoutedRequestPolicy.rejectionReason("DELETE", "/api/user/favorites/3", "a=1"));
        assertNull(RoutedRequestPolicy.rejectionReason("POST", "/user/api/register", null));
        assertNull(RoutedRequestPolicy.rejectionReason("PUT", "/api/admin/posts/7", null));
        assertNull(RoutedRequestPolicy.rejectionReason("POST", "/a2a", null));
        assertNull(RoutedRequestPolicy.rejectionReason("GET", "/search/internal/deep", "q=x&page=1"));
    }

    @Test
    void shouldRefuseManagementSyncAndArbitraryReads() {
        assertEquals("ROUTED_PATH_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("POST", "/q/dev-ui", null));
        assertEquals("ROUTED_PATH_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("POST", "/sync/v1/requests", null));
        assertEquals("ROUTED_PATH_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("POST", "/grpc/x", null));
        assertEquals("ROUTED_PATH_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("GET", "/q/metrics", null));
        assertEquals("ROUTED_PATH_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("GET", "/api/admin/users", null));
        assertEquals("ROUTED_METHOD_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("HEAD", "/api/comments", null));
        assertEquals("ROUTED_METHOD_NOT_ALLOWED", RoutedRequestPolicy.rejectionReason("TRACE", "/api/comments", null));
    }

    @Test
    void shouldRefusePathsTheRouterWouldReinterpret() {
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "/api/../q/dev-ui", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "/api/%2e%2e/q/x", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "//127.0.0.1/api", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "/api/x;y", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "/api/x?y=1", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "/api/x\r\nHost: a", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", "api/comments", null));
        assertEquals("ROUTED_PATH_INVALID", RoutedRequestPolicy.rejectionReason("POST", null, null));
        assertEquals("ROUTED_QUERY_INVALID", RoutedRequestPolicy.rejectionReason("POST", "/api/comments", "a=1#x"));
    }

    @Test
    void shouldDropSpoofableForwardingAndInternalHeaders() {
        for (String header : new String[]{"X-Forwarded-For", "x-forwarded-host", "Forwarded", "X-Real-IP",
                "EO-Connecting-IP", "CF-Connecting-IP", "True-Client-IP", "X-WindBlog-Routed-From-Edge",
                "X-WESP-Node-Id", "Host", "Transfer-Encoding", "Upgrade", "Expect"}) {
            assertFalse(PrimaryRoutedHttpExecutor.isForwardableHeader(header), header);
        }
        for (String header : new String[]{"Content-Type", "Cookie", "Authorization", "X-XSRF-TOKEN",
                "X-Admin-Step-Up", "Idempotency-Key", "Accept-Language", "User-Agent"}) {
            assertTrue(PrimaryRoutedHttpExecutor.isForwardableHeader(header), header);
        }
    }

    @Test
    void shouldRejectBeforeContactingThePrimaryListener() {
        PrimaryRoutedHttpExecutor executor = new PrimaryRoutedHttpExecutor();
        RoutedHttpExchange.Response response = executor.execute(new RoutedHttpExchange.Request(
                "POST", "/q/arc/beans", null, java.util.Map.of(), new byte[0]));

        assertEquals(403, response.status());
        assertEquals("ROUTED_PATH_NOT_ALLOWED", response.errorMessage());
    }
}
