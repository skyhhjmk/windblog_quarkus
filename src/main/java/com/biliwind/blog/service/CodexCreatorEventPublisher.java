package com.biliwind.blog.service;

import com.biliwind.blog.service.ai.CodexCreatorHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes only named WindBlog domain events through the durable Outbox. */
@ApplicationScoped
public class CodexCreatorEventPublisher {
    private static final String DEFAULT_PROFILE = "codex-default";

    @Inject
    OutboxEventService outbox;

    @ConfigProperty(name = "windblog.ai.codex-creator.events-enabled", defaultValue = "false")
    boolean eventsEnabled;

    public void commentCreated(Long commentId, Long postId, String content, String traceId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("commentId", commentId);
        input.put("postId", postId);
        input.put("content", content);
        publish("comment.created", "COMMENT", commentId, input, traceId, "moderate");
    }

    public void linkApplicationCreated(Long linkId, String url, String name, String traceId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("linkId", linkId);
        input.put("url", url);
        input.put("name", name);
        publish("link.application.created", "LINK", linkId, input, traceId, "moderate");
    }

    public void postRevisionUpdated(Long postId, Long revisionId, Map<String, Object> input, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>(input == null ? Map.of() : input);
        payload.put("postId", postId);
        payload.put("revisionId", revisionId);
        publish("post.revision.updated", "POST", postId, payload, traceId, "summarize");
    }

    public void postPublished(Long postId, Map<String, Object> input, String traceId) {
        publish("post.published", "POST", postId, input == null ? Map.of("postId", postId) : input, traceId, "summarize");
    }

    private void publish(String eventType, String aggregateType, Long aggregateId,
                         Map<String, Object> input, String traceId, String operation) {
        if (!eventsEnabled) return;
        String effectiveTrace = traceId == null || traceId.isBlank() ? java.util.UUID.randomUUID().toString() : traceId;
        String key = eventType + ":" + aggregateType + ":" + aggregateId + ":" + sha256(input.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("aggregateType", aggregateType);
        payload.put("aggregateId", String.valueOf(aggregateId));
        payload.put("operation", operation);
        payload.put("profileId", DEFAULT_PROFILE);
        payload.put("input", input);
        payload.put("idempotencyKey", key);
        payload.put("traceId", effectiveTrace);
        payload.put("promptVersion", "1");
        outbox.enqueue(key, "CODEX_CREATOR_EVENT", aggregateType, String.valueOf(aggregateId), payload, effectiveTrace);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create Codex Creator event key", exception);
        }
    }
}
