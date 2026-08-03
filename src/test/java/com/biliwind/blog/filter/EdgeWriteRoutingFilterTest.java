package com.biliwind.blog.filter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EdgeWriteRoutingFilterTest {

    @Test
    void shouldReadBodyWithinConfiguredLimit() throws Exception {
        byte[] body = "small-body".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(body, EdgeWriteRoutingFilter.readLimitedBody(
                new ByteArrayInputStream(body), body.length));
    }

    @Test
    void shouldRejectBodyThatExceedsConfiguredLimit() {
        byte[] body = "too-large".getBytes(StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> EdgeWriteRoutingFilter.readLimitedBody(
                new ByteArrayInputStream(body), body.length - 1L));
    }
}
