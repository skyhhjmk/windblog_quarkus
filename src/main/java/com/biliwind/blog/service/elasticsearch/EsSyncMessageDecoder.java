package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;

/** 将 RabbitMQ 适配器交付的 JSON 载荷统一解码为 ES 同步任务。 */
@ApplicationScoped
public class EsSyncMessageDecoder {

    private final ObjectMapper objectMapper;

    @Inject
    public EsSyncMessageDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EsSyncTask decode(Object payload) throws Exception {
        if (payload instanceof EsSyncTask task) {
            return validate(task);
        }
        if (payload instanceof Buffer buffer) {
            return decodeBytes(buffer.getBytes());
        }
        if (payload instanceof byte[] bytes) {
            return decodeBytes(bytes);
        }
        if (payload instanceof JsonObject jsonObject) {
            return validate(objectMapper.convertValue(jsonObject.getMap(), EsSyncTask.class));
        }
        if (payload instanceof String text) {
            return decodeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("ES 同步消息载荷类型不受支持: "
                + (payload == null ? "null" : payload.getClass().getName()));
    }

    private EsSyncTask decodeBytes(byte[] bytes) throws Exception {
        return validate(objectMapper.readValue(bytes, EsSyncTask.class));
    }

    private EsSyncTask validate(EsSyncTask task) {
        if (task == null || task.postId() == null || task.postId() <= 0
                || task.actionType() == null
                || !("UPDATE".equalsIgnoreCase(task.actionType())
                || "DELETE".equalsIgnoreCase(task.actionType()))) {
            throw new IllegalArgumentException("ES 同步消息字段不完整或操作类型无效");
        }
        return new EsSyncTask(task.postId(), task.actionType().toUpperCase(java.util.Locale.ROOT));
    }
}
