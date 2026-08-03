package com.biliwind.blog.service;

import com.biliwind.blog.model.ContentAccessTicket;
import com.biliwind.blog.model.MediaDownloadEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;

@ApplicationScoped
public class MediaDownloadAuditService {

    @ConfigProperty(name = "security.event-hash-secret", defaultValue = "windblog-dev-event-hash-secret")
    String eventHashSecret;

    @ConfigProperty(name = "windblog.node.id", defaultValue = "primary")
    String nodeId;

    @ConfigProperty(name = "security.media-download-event-retention-days", defaultValue = "90")
    int retentionDays;

    @Transactional
    public void recordAllowed(ContentAccessTicket ticket, Long mediaId, String clientIp, String userAgent, Long bytesSent) {
        recordAllowed(ticket, mediaId, clientIp, userAgent, null, bytesSent);
    }

    @Transactional
    public void recordAllowed(ContentAccessTicket ticket, Long mediaId, String clientIp,
                              String userAgent, String referrer, Long bytesSent) {
        record(ticket == null ? null : ticket.postId, mediaId, ticket == null ? null : ticket.id,
                ticket == null ? null : ticket.subjectId, clientIp, userAgent, referrer,
                bytesSent, "ALLOWED", null);
    }

    @Transactional
    public void recordDenied(Long postId, Long mediaId, Long ticketId, Long subjectId,
                             String clientIp, String userAgent, String denyReason) {
        recordDenied(postId, mediaId, ticketId, subjectId, clientIp, userAgent, null, denyReason);
    }

    @Transactional
    public void recordDenied(Long postId, Long mediaId, Long ticketId, Long subjectId,
                             String clientIp, String userAgent, String referrer, String denyReason) {
        record(postId, mediaId, ticketId, subjectId, clientIp, userAgent, referrer,
                null, "DENIED", denyReason);
    }

    @Transactional
    public void recordFailed(ContentAccessTicket ticket, Long mediaId, Long subjectId,
                             String clientIp, String userAgent, Long bytesSent, String reason) {
        record(ticket == null ? null : ticket.postId, mediaId, ticket == null ? null : ticket.id,
                subjectId, clientIp, userAgent, null, bytesSent, "FAILED", reason);
    }

    private void record(Long postId, Long mediaId, Long ticketId, Long subjectId,
                        String clientIp, String userAgent, String referrer, Long bytesSent,
                        String status, String denyReason) {
        MediaDownloadEvent event = new MediaDownloadEvent();
        event.mediaId = mediaId;
        event.postId = postId;
        event.ticketId = ticketId;
        event.subjectHash = subjectId == null ? null : hash(String.valueOf(subjectId));
        event.ipHash = hash(clientIp);
        event.userAgentHash = hash(userAgent);
        event.referrerHash = hashReferrer(referrer);
        event.bytesSent = bytesSent;
        event.status = status;
        event.denyReason = denyReason;
        event.nodeId = nodeId;
        event.persist();
    }

    public String hashSubject(Long userId) {
        return userId == null ? null : hash(String.valueOf(userId));
    }

    public String hashReferrer(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        String normalized = referrer.trim().toLowerCase();
        try {
            URI uri = URI.create(normalized);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                normalized = uri.getHost().toLowerCase();
            }
        } catch (Exception ignored) {
            // Querying by a raw domain remains deterministic even for malformed legacy values.
        }
        return hash(normalized);
    }

    @Scheduled(every = "1h", identity = "media-download-audit-retention")
    @Transactional
    void purgeExpiredEvents() {
        if (retentionDays < 1) {
            return;
        }
        MediaDownloadEvent.delete("createdAt < ?1",
                java.time.OffsetDateTime.now().minusDays(retentionDays));
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(eventHashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成下载审计摘要", exception);
        }
    }
}
