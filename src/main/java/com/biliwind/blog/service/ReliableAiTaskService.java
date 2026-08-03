package com.biliwind.blog.service;

import com.biliwind.blog.service.ai.AiAuditTask;
import com.biliwind.blog.service.ai.AiSummaryTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists AI task intent before RabbitMQ publication can be attempted. */
@ApplicationScoped
public class ReliableAiTaskService {

    @Inject
    OutboxEventService outboxEventService;

    @Inject
    ObjectMapper objectMapper;

    public void enqueueSummary(AiSummaryTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("postId", task.postId());
        payload.put("content", task.content());
        payload.put("priority", task.priority());
        payload.put("retryCount", task.retryCount());
        payload.put("performedBy", task.performedBy());
        enqueue("AI_SUMMARY", "POST", String.valueOf(task.postId()), payload);
    }

    public void enqueueAudit(AiAuditTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commentId", task.commentId());
        payload.put("content", task.content());
        payload.put("retryCount", task.retryCount());
        enqueue("AI_AUDIT", "COMMENT", String.valueOf(task.commentId()), payload);
    }

    private void enqueue(String type, String aggregateType, String aggregateId,
                         Map<String, Object> payload) {
        try {
            String serialized = objectMapper.writeValueAsString(payload);
            String eventKey = type + ":" + aggregateId + ":" + digest(serialized);
            outboxEventService.enqueue(eventKey, type, aggregateType, aggregateId, payload, null);
        } catch (Exception exception) {
            throw new IllegalStateException("AI 任务写入 outbox 失败", exception);
        }
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("AI 任务幂等键生成失败", exception);
        }
    }
}
