package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages server-sent event (SSE) streams for real-time post changes,
 * notifying admin editors of background updates (AI summary, translation, multi-session edits).
 */
@ApplicationScoped
@RegisterForReflection
public class AdminPostEventService {

    private static final Logger LOG = Logger.getLogger(AdminPostEventService.class);

    private final Map<Long, BroadcastProcessor<String>> postProcessors = new ConcurrentHashMap<>();

    public Multi<String> getEventStream(Long postId) {
        BroadcastProcessor<String> processor = postProcessors.computeIfAbsent(
                postId,
                k -> BroadcastProcessor.create()
        );

        Multi<String> initial = Multi.createFrom().item("{\"type\":\"CONNECTED\",\"postId\":" + postId + "}");
        Multi<String> heartbeat = Multi.createFrom().ticks().every(Duration.ofSeconds(20))
                .onItem().transform(tick -> "{\"type\":\"HEARTBEAT\",\"timestamp\":" + System.currentTimeMillis() + "}");

        return Multi.createBy().merging().streams(initial, processor, heartbeat);
    }

    public void broadcastPostUpdated(Post post, String source) {
        if (post == null || post.id == null) {
            return;
        }
        BroadcastProcessor<String> processor = postProcessors.get(post.id);
        if (processor == null) {
            return;
        }

        String json = buildJson(post, source);
        try {
            processor.onNext(json);
        } catch (Exception e) {
            LOG.warnf("Failed to broadcast post updated event for post %d: %s", post.id, e.getMessage());
        }
    }

    public static String buildJson(Post post, String source) {
        long revId = post.currentRevision == null ? 0L : (post.currentRevision.id == null ? 0L : post.currentRevision.id);
        int revNum = post.currentRevision == null ? 0 : post.currentRevision.revisionNumber;
        int ver = post.version == null ? 0 : post.version;
        String slug = post.slug == null ? "" : post.slug;
        String updated = post.updatedAt == null ? OffsetDateTime.now().toString() : post.updatedAt.toString();
        String src = source == null ? "SYSTEM" : source;

        return String.format(
                "{\"type\":\"POST_UPDATED\",\"postId\":%d,\"slug\":\"%s\",\"version\":%d," +
                "\"currentRevisionId\":%d,\"currentRevisionNumber\":%d,\"updatedAt\":\"%s\",\"source\":\"%s\"}",
                post.id, slug, ver, revId, revNum, updated, src
        );
    }
}
