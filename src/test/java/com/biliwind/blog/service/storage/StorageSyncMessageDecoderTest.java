package com.biliwind.blog.service.storage;

import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageSyncMessageDecoderTest {

    private final StorageSyncMessageDecoder decoder = new StorageSyncMessageDecoder(new ObjectMapper());

    @Test
    void shouldDecodeRabbitBufferAndJsonObjectPayloads() throws Exception {
        String payload = "{\"mediaId\":7,\"storageClassName\":\"archive\","
                + "\"variantType\":\"WEBP\",\"retryCount\":2}";

        StorageSyncMessage bufferMessage = decoder.decode(Buffer.buffer(payload));
        StorageSyncMessage jsonMessage = decoder.decode(new JsonObject(payload));

        assertEquals(7L, bufferMessage.mediaId());
        assertEquals("archive", jsonMessage.storageClassName());
        assertEquals(2, jsonMessage.retryCount());
    }

    @Test
    void shouldRejectInvalidStorageSyncPayload() {
        assertThrows(Exception.class, () -> decoder.decode(
                Buffer.buffer("{\"mediaId\":7,\"storageClassName\":\"archive\","
                        + "\"variantType\":\"WEBP\",\"retryCount\":-1}")));
    }
}
