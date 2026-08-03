package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EsSyncMessageDecoderTest {

    private final EsSyncMessageDecoder decoder = new EsSyncMessageDecoder(new ObjectMapper());

    @Test
    void shouldDecodeRabbitBufferAndJsonObjectPayloads() throws Exception {
        EsSyncTask bufferTask = decoder.decode(Buffer.buffer("{\"postId\":42,\"actionType\":\"update\"}"));
        EsSyncTask jsonTask = decoder.decode(new JsonObject().put("postId", 43).put("actionType", "DELETE"));

        assertEquals(new EsSyncTask(42L, "UPDATE"), bufferTask);
        assertEquals(new EsSyncTask(43L, "DELETE"), jsonTask);
    }

    @Test
    void shouldRejectInvalidEsSyncPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(new EsSyncTask(0L, "UPDATE")));
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(new EsSyncTask(1L, "INDEX")));
    }
}
