package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeExternalHttpServiceTest {

    private final SafeExternalHttpService service = new SafeExternalHttpService();

    @Test
    void shouldRejectPrivateAndUnsupportedExternalAddresses() {
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://127.0.0.1/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://[::1]/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://100.64.0.1/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://169.254.1.1/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://198.18.0.1/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://192.0.2.1/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://[::ffff:c0a8:101]/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://[2001:db8::1]/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("http://user:password@example.com/internal"));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublicHttpUri("ftp://example.com/file"));
    }

    @Test
    void shouldRejectOversizedResponseHeaders() {
        Header[] headers = {new BasicHeader("Location", "x".repeat(8 * 1024 + 1))};

        assertThrows(IllegalArgumentException.class,
                () -> SafeExternalHttpService.validateResponseHeaders(headers));
    }

    @Test
    void shouldRejectInvalidResponseLimit() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.get("https://example.com", "test", 0));
        assertEquals("外部响应大小限制无效", exception.getMessage());
    }

    @Test
    void shouldEnforceLimitAfterResponseDecompression() {
        byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
                SafeExternalHttpService.readLimitedStream(
                        new ByteArrayInputStream(body), -1, 4));
    }
}
