package com.biliwind.blog.service;

import com.biliwind.blog.model.OutboxEvent;
import com.biliwind.blog.context.AdminAuditRequestContext;
import com.biliwind.blog.service.elasticsearch.EsSyncTask;
import com.biliwind.blog.service.ai.AiAuditTask;
import com.biliwind.blog.service.ai.AiSummaryTask;
import com.biliwind.blog.service.ai.AiTaskProducer;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 可靠事件边界：状态写入和事件写入在同一事务，发布失败由租约和重试恢复。
 */
@ApplicationScoped
public class OutboxEventService {
    private static final Logger log = Logger.getLogger(OutboxEventService.class);

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Instance<AdminAuditRequestContext> auditRequestContext;

    @Inject
    @Channel("es-sync-tasks")
    Instance<Emitter<EsSyncTask>> esSyncEmitter;

    @Inject
    AiTaskProducer aiTaskProducer;

    @Inject
    SecurityMetricsService securityMetricsService;

    @Inject
    @Channel("storage-sync-tasks")
    Instance<Emitter<StorageSyncMessage>> storageSyncEmitter;

    @Inject
    Instance<MediaManagementService> mediaManagementService;

    @Inject
    Instance<MediaReferenceRebuildService> mediaReferenceRebuildService;

    @Inject
    Instance<com.biliwind.blog.service.edge.EdgeDataSyncService> edgeDataSyncService;

    @Inject
    Instance<EmailDeliveryService> emailDeliveryService;

    @ConfigProperty(name = "windblog.outbox.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "windblog.outbox.publish-timeout", defaultValue = "30S")
    Duration publishTimeout;

    @ConfigProperty(name = "windblog.outbox.max-events-per-cycle", defaultValue = "100")
    int maxEventsPerCycle;

    @ConfigProperty(name = "windblog.outbox.lease-duration", defaultValue = "15M")
    Duration leaseDuration;

    @Transactional
    public void enqueue(String eventKey, String eventType, String aggregateType, String aggregateId,
                        Map<String, Object> payload, String traceId) {
        if (!enabled) {
            return;
        }
        OutboxEvent existing = OutboxEvent.find("eventKey", eventKey).firstResult();
        if (existing != null) {
            return;
        }
        OutboxEvent event = new OutboxEvent();
        event.eventKey = eventKey;
        event.eventType = eventType;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.payload = payload;
        event.traceId = resolveTraceId(traceId);
        event.availableAt = OffsetDateTime.now();
        event.createdAt = event.availableAt;
        event.persist();
    }

    @Scheduled(every = "5s", identity = "outbox-dispatcher")
    void dispatchPendingEvents() {
        if (!enabled) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        int processedEvents = 0;
        int cycleLimit = Math.max(1, Math.min(maxEventsPerCycle, 1000));
        while (processedEvents < cycleLimit) {
            Long eventId = claimNext(owner);
            if (eventId == null) {
                return;
            }
            processedEvents = processedEvents + 1;
            OutboxEvent event = OutboxEvent.findById(eventId);
            if (event == null) {
                continue;
            }
            try {
                dispatch(event);
                markPublished(eventId, owner);
                securityMetricsService.increment("outbox.published", event.eventType);
            } catch (Exception exception) {
                markFailed(eventId, owner, exception.getMessage());
                securityMetricsService.increment("outbox.failed", event.eventType);
                log.warnf("outbox event dispatch failed, id=%d, type=%s", eventId, event.eventType);
            }
        }
    }

    @Transactional
    Long claimNext(String owner) {
        Duration effectiveLease = leaseDuration;
        if (effectiveLease == null || effectiveLease.isNegative() || effectiveLease.isZero()) {
            effectiveLease = Duration.ofMinutes(2);
        }
        OffsetDateTime leaseUntil = OffsetDateTime.now().plus(effectiveLease);
        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                        "update outbox_events set status = 'IN_FLIGHT', locked_until = ?1, lock_owner = ?2, "
                                + "attempt_count = attempt_count + 1 "
                                + "where id = (select id from outbox_events where status in ('PENDING', 'IN_FLIGHT') "
                                + "and available_at <= now() and (locked_until is null or locked_until < now()) "
                                + "order by id limit 1 for update skip locked) returning id")
                .setParameter(1, leaseUntil)
                .setParameter(2, owner)
                .getResultList();
        refreshMetrics();
        return ids.isEmpty() ? null : ids.get(0).longValue();
    }

    private void dispatch(OutboxEvent event) throws Exception {
        if ("ES_SYNC".equals(event.eventType)) {
            Object postIdValue = event.payload == null ? null : event.payload.get("postId");
            Object actionValue = event.payload == null ? null : event.payload.get("action");
            if (postIdValue == null || actionValue == null) {
                throw new IllegalArgumentException("ES_SYNC outbox payload 不完整");
            }
            Long postId = objectMapper.convertValue(postIdValue, Long.class);
            String action = objectMapper.convertValue(actionValue, String.class);
            awaitPublish(esSyncEmitter.get().send(new EsSyncTask(postId, action)));
            return;
        }
        if ("AI_SUMMARY".equals(event.eventType)) {
            AiSummaryTask task = objectMapper.convertValue(event.payload, AiSummaryTask.class);
            aiTaskProducer.sendSummaryTask(task);
            return;
        }
        if ("AI_AUDIT".equals(event.eventType)) {
            AiAuditTask task = objectMapper.convertValue(event.payload, AiAuditTask.class);
            aiTaskProducer.sendAuditTask(task);
            return;
        }
        if ("STORAGE_SYNC".equals(event.eventType)) {
            StorageSyncMessage task = objectMapper.convertValue(event.payload, StorageSyncMessage.class);
            awaitPublish(storageSyncEmitter.get().send(task));
            return;
        }
        if ("MEDIA_PROCESS".equals(event.eventType)) {
            Object mediaIdValue = event.payload == null ? null : event.payload.get("mediaId");
            if (mediaIdValue == null) {
                throw new IllegalArgumentException("MEDIA_PROCESS outbox payload 不完整");
            }
            mediaManagementService.get().processPendingMedia(
                    objectMapper.convertValue(mediaIdValue, Long.class));
            return;
        }
        if ("MEDIA_REFERENCE_REBUILD".equals(event.eventType)) {
            Object jobIdValue = event.payload == null ? null : event.payload.get("jobId");
            if (jobIdValue == null) {
                throw new IllegalArgumentException("MEDIA_REFERENCE_REBUILD outbox payload 不完整");
            }
            mediaReferenceRebuildService.get().runBatch(
                    objectMapper.convertValue(jobIdValue, Long.class));
            return;
        }
        if ("EDGE_SYNC".equals(event.eventType)) {
            String entityType = objectMapper.convertValue(event.payload.get("entityType"), String.class);
            Long entityId = objectMapper.convertValue(event.payload.get("entityId"), Long.class);
            String action = objectMapper.convertValue(event.payload.get("action"), String.class);
            edgeDataSyncService.get().dispatchOutboxSync(entityType, entityId, action);
            return;
        }
        if ("EMAIL_DELIVERY".equals(event.eventType)) {
            if (event.payload == null) {
                throw new IllegalArgumentException("EMAIL_DELIVERY outbox payload 为空");
            }
            String scenario = objectMapper.convertValue(event.payload.get("scenario"), String.class);
            String recipientAddress = objectMapper.convertValue(
                    event.payload.get("recipientAddress"), String.class);
            String subject = objectMapper.convertValue(event.payload.get("subject"), String.class);
            String htmlContent = objectMapper.convertValue(event.payload.get("htmlContent"), String.class);
            Long channelGroupId = objectMapper.convertValue(event.payload.get("channelGroupId"), Long.class);
            Long channelId = objectMapper.convertValue(event.payload.get("channelId"), Long.class);
            if (scenario == null || recipientAddress == null || subject == null || htmlContent == null) {
                throw new IllegalArgumentException("EMAIL_DELIVERY outbox payload 不完整");
            }
            emailDeliveryService.get().persistQueuedDelivery(
                    scenario, recipientAddress, subject, htmlContent, channelGroupId, channelId);
            return;
        }
        throw new IllegalArgumentException("没有注册的 outbox event handler: " + event.eventType);
    }

    @Transactional
    void markPublished(Long eventId, String owner) {
        OutboxEvent event = OutboxEvent.find("id = ?1 and lockOwner = ?2", eventId, owner).firstResult();
        if (event == null) {
            return;
        }
        event.status = "PUBLISHED";
        event.publishedAt = OffsetDateTime.now();
        event.lockedUntil = null;
        event.lockOwner = null;
        refreshMetrics();
    }

    @Transactional
    void markFailed(Long eventId, String owner, String error) {
        OutboxEvent event = OutboxEvent.find("id = ?1 and lockOwner = ?2", eventId, owner).firstResult();
        if (event == null) {
            return;
        }
        event.status = event.attemptCount >= 8 ? "FAILED" : "PENDING";
        event.availableAt = OffsetDateTime.now().plusSeconds(Math.min(300, 5L * event.attemptCount * event.attemptCount));
        event.lastError = truncate(error);
        event.lockedUntil = null;
        event.lockOwner = null;
        refreshMetrics();
    }

    private void refreshMetrics() {
        Number pending = (Number) entityManager.createNativeQuery(
                "select count(*) from outbox_events where status = 'PENDING'").getSingleResult();
        Number inFlight = (Number) entityManager.createNativeQuery(
                "select count(*) from outbox_events where status = 'IN_FLIGHT'").getSingleResult();
        Number failed = (Number) entityManager.createNativeQuery(
                "select count(*) from outbox_events where status = 'FAILED'").getSingleResult();
        Number lag = (Number) entityManager.createNativeQuery(
                "select coalesce(extract(epoch from (now() - min(created_at))), 0) "
                        + "from outbox_events where status in ('PENDING', 'IN_FLIGHT')")
                .getSingleResult();
        securityMetricsService.setGauge("outbox.pending", pending.longValue());
        securityMetricsService.setGauge("outbox.in_flight", inFlight.longValue());
        securityMetricsService.setGauge("outbox.failed", failed.longValue());
        securityMetricsService.setGauge("outbox.lag_seconds", lag.longValue());
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private String resolveTraceId(String requestedTraceId) {
        if (requestedTraceId != null && !requestedTraceId.isBlank()) {
            return requestedTraceId;
        }
        try {
            AdminAuditRequestContext context = auditRequestContext.get();
            return context.getRequestId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void awaitPublish(CompletionStage<?> completion) {
        if (completion == null) {
            throw new IllegalStateException("消息发布器未返回确认结果");
        }
        long timeoutMillis = Math.max(1000L, publishTimeout.toMillis());
        try {
            completion.toCompletableFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 RabbitMQ 消息确认时被中断", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("RabbitMQ 消息未在期限内确认", exception);
        }
    }
}
