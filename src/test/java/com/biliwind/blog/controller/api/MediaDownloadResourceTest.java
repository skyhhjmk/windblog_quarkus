package com.biliwind.blog.controller.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaDownloadResourceTest {

    @Test
    void shouldStopStreamingAtConfiguredByteLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = "abcdef".getBytes(StandardCharsets.UTF_8);

        assertEquals(4L, MediaDownloadResource.writeLimitedChunk(output, buffer, 4, 0, 4));
        IOException exception = assertThrows(IOException.class,
                () -> MediaDownloadResource.writeLimitedChunk(output, buffer, buffer.length, 4, 4));

        assertEquals("受保护下载超过单文件字节上限", exception.getMessage());
        assertEquals("abcd", output.toString(StandardCharsets.UTF_8));
    }
}
