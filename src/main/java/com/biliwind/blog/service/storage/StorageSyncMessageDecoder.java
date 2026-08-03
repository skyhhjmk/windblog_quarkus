package com.biliwind.blog.service.storage;

import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** 将 RabbitMQ 适配器交付的 JSON 载荷统一解码为存储同步任务。 */
@ApplicationScoped
public class StorageSyncMessageDecoder {

    private final ObjectMapper objectMapper;

    @Inject
    public StorageSyncMessageDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StorageSyncMessage decode(Object payload) throws Exception {
        if (payload instanceof StorageSyncMessage message) {
            return validate(message);
        }
        if (payload instanceof Buffer buffer) {
            return decodeBytes(buffer.getBytes());
        }
        if (payload instanceof byte[] bytes) {
            return decodeBytes(bytes);
        }
        if (payload instanceof JsonObject jsonObject) {
            return validate(objectMapper.convertValue(jsonObject.getMap(), StorageSyncMessage.class));
        }
        if (payload instanceof String text) {
            return decodeBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("存储同步消息载荷类型不受支持: "
                + (payload == null ? "null" : payload.getClass().getName()));
    }

    private StorageSyncMessage decodeBytes(byte[] bytes) throws Exception {
        return validate(objectMapper.readValue(bytes, StorageSyncMessage.class));
    }

    private StorageSyncMessage validate(StorageSyncMessage message) {
        if (message == null || message.mediaId() == null || message.storageClassName() == null
                || message.storageClassName().isBlank() || message.variantType() == null
                || message.variantType().isBlank() || message.retryCount() < 0) {
            throw new IllegalArgumentException("存储同步消息字段不完整");
        }
        return message;
    }
}
