package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiStreamTextTest {

    @Test
    void joinsContentChunks() {
        assertEquals("Hello world", AiStreamText.join(List.of(
                "{\"type\":\"content\",\"content\":\"Hello \"}",
                "{\"type\":\"content\",\"content\":\"world\"}")));
    }

    @Test
    void keepsPlainTextAndStripsDataPrefix() {
        assertEquals("数据", AiStreamText.join(List.of("data: 数", "据")));
    }

    @Test
    void failsOnErrorEnvelope() {
        assertThrows(IllegalStateException.class,
                () -> AiStreamText.join(List.of("{\"type\":\"error\",\"content\":\"boom\"}")));
    }
}
