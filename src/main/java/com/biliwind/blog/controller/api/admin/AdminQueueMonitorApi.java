package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.DeadLetterMessage;
import com.biliwind.blog.model.OutboxEvent;
import com.biliwind.blog.service.ai.AiTaskRuntimeTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 队列监控 API
 * 聚合 RabbitMQ 实时状态、Outbox 投递管道、死信记录与内存中正在执行的 AI 任务。
 */
@Path("/api/admin/queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "QueueMonitor", description = "队列监控管理")
public class AdminQueueMonitorApi {

    private static final Logger log = LoggerFactory.getLogger(AdminQueueMonitorApi.class);

    private record QueueSpec(
            String name,
            String description,
            String deadLetterQueue,
            String outboxEventType,
            boolean isDeadLetter) {
    }

    private static final List<QueueSpec> MONITORED_QUEUES = List.of(
            new QueueSpec("ai-audit-tasks", "AI 评论审核任务队列", "ai-audit-dead-letter", "AI_AUDIT", false),
            new QueueSpec("ai-summary-tasks", "AI 摘要任务队列", "ai-summary-dead-letter", "AI_SUMMARY", false),
            new QueueSpec("es-sync-tasks", "ES 搜索索引同步队列", "es-sync-tasks-dead-letter", "ES_SYNC", false),
            new QueueSpec("storage-sync-tasks", "对象存储同步队列", "storage-sync-dead-letter", "STORAGE_SYNC", false),
            new QueueSpec("ai-audit-dead-letter", "AI 审核死信队列", "", null, true),
            new QueueSpec("ai-summary-dead-letter", "AI 摘要死信队列", "", null, true),
            new QueueSpec("es-sync-tasks-dead-letter", "ES 同步死信队列", "", null, true),
            new QueueSpec("storage-sync-dead-letter", "对象存储同步死信队列", "", null, true)
    );

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Inject
    AiTaskRuntimeTracker runtimeTracker;

    @ConfigProperty(name = "rabbitmq-host", defaultValue = "localhost")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port", defaultValue = "5672")
    int rabbitmqPort;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUsername;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    @ConfigProperty(name = "windblog.outbox.enabled", defaultValue = "true")
    boolean outboxEnabled;

    @ConfigProperty(name = "windblog.node.role", defaultValue = "primary")
    String nodeRole;

    /**
     * 获取所有队列的实时监控信息、Outbox 管道统计、活跃执行任务与最近执行记录。
     */
    @GET
    @Transactional
    @Operation(summary = "获取队列列表与监控总览", description = "聚合 RabbitMQ 队列、Outbox、死信与活跃任务状态")
    public Response getQueues() {
        Map<String, OutboxCounts> outboxByType = loadOutboxCountsByEventType();
        Map<String, Long> unprocessedDlqBySource = loadUnprocessedDeadLettersBySource();
        Map<String, RabbitQueueProbe> brokerProbes = new HashMap<>();

        boolean brokerConnected = false;
        String brokerError = null;

        try {
            ConnectionFactory factory = createConnectionFactory();
            factory.setConnectionTimeout(3000);
            try (Connection connection = factory.newConnection()) {
                brokerConnected = true;
                for (QueueSpec spec : MONITORED_QUEUES) {
                    brokerProbes.put(spec.name(), probeQueuePassive(connection, spec.name()));
                }
            }
        } catch (Exception e) {
            brokerError = SensitiveMessageSanitizer.sanitize(e.getMessage());
            log.warn("连接 RabbitMQ 失败，将降级返回 Outbox 与内存任务监控数据: {}", brokerError);
        }

        List<Map<String, Object>> queues = new ArrayList<>(MONITORED_QUEUES.size());
        for (QueueSpec spec : MONITORED_QUEUES) {
            RabbitQueueProbe probe = brokerProbes.get(spec.name());
            OutboxCounts outbox = spec.outboxEventType() != null
                    ? outboxByType.getOrDefault(spec.outboxEventType(), OutboxCounts.EMPTY)
                    : OutboxCounts.EMPTY;
            int activeCount = runtimeTracker.activeCountForQueue(spec.name());

            int readyCount = probe != null && probe.exists ? probe.messageCount : 0;
            int consumerCount = probe != null && probe.exists ? probe.consumerCount : 0;
            boolean exists = probe != null && probe.exists;

            long dlqBrokerCount = 0;
            if (!spec.deadLetterQueue().isEmpty()) {
                RabbitQueueProbe dlqProbe = brokerProbes.get(spec.deadLetterQueue());
                if (dlqProbe != null && dlqProbe.exists) {
                    dlqBrokerCount = dlqProbe.messageCount;
                }
            }
            long unprocessedDlq = spec.isDeadLetter()
                    ? 0L
                    : unprocessedDlqBySource.getOrDefault(spec.name(), 0L);

            String healthStatus = resolveQueueHealth(
                    brokerConnected, exists, readyCount, consumerCount, activeCount,
                    outbox, unprocessedDlq, spec.isDeadLetter());

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", spec.name());
            info.put("description", spec.description());
            info.put("isDeadLetter", spec.isDeadLetter());
            info.put("exists", exists);
            info.put("status", healthStatus);
            info.put("messageCount", readyCount);
            info.put("consumerCount", consumerCount);
            info.put("activeTaskCount", activeCount);
            info.put("outboxEventType", spec.outboxEventType());
            info.put("outboxPendingCount", outbox.pending);
            info.put("outboxInFlightCount", outbox.inFlight);
            info.put("outboxFailedCount", outbox.failed);
            info.put("deadLetterQueue", spec.deadLetterQueue());
            info.put("deadLetterMessageCount", (int) (dlqBrokerCount + unprocessedDlq));
            info.put("unprocessedDeadLetterCount", unprocessedDlq);
            if (probe != null && probe.error != null) {
                info.put("error", probe.error);
            } else if (!brokerConnected && brokerError != null) {
                info.put("error", brokerError);
            }
            queues.add(info);
        }

        Map<String, Object> summary = buildSummary(brokerConnected, brokerError, outboxByType);
        List<AiTaskRuntimeTracker.ActiveTaskSnapshot> activeTasks = runtimeTracker.listActiveTasks();
        List<AiTaskRuntimeTracker.RecentTaskEvent> recentEvents = runtimeTracker.listRecentEvents(30);
        List<Map<String, Object>> recentOutboxIssues = loadRecentOutboxIssues(20);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", queues);
        body.put("summary", summary);
        body.put("activeTasks", activeTasks);
        body.put("recentEvents", recentEvents);
        body.put("recentOutboxIssues", recentOutboxIssues);

        return Response.ok(body).build();
    }

    /**
     * 获取单个队列的详细信息
     */
    @GET
    @Path("/{queueName}")
    @Transactional
    @Operation(summary = "获取队列详情", description = "获取指定队列的详细信息")
    public Response getQueueDetail(@PathParam("queueName") String queueName) {
        try {
            ConnectionFactory factory = createConnectionFactory();
            factory.setConnectionTimeout(3000);
            try (Connection connection = factory.newConnection()) {
                RabbitQueueProbe probe = probeQueuePassive(connection, queueName);
                if (!probe.exists) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of(
                                    "success", false,
                                    "message", "队列不存在：" + queueName
                            )).build();
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", queueName);
                result.put("messageCount", probe.messageCount);
                result.put("consumerCount", probe.consumerCount);
                result.put("activeTaskCount", runtimeTracker.activeCountForQueue(queueName));
                result.put("isDurable", true);

                return Response.ok(Map.of(
                        "success", true,
                        "data", result
                )).build();
            }
        } catch (IOException | TimeoutException e) {
            log.error("获取队列详情失败，queueName={}", queueName, e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "success", false,
                            "message", "无法连接 RabbitMQ：" + SensitiveMessageSanitizer.sanitize(e.getMessage())
                    )).build();
        } catch (Exception e) {
            log.error("获取队列详情失败，queueName={}", queueName, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "success", false,
                            "message", "获取队列详情失败：" + SensitiveMessageSanitizer.sanitize(e.getMessage())
                    )).build();
        }
    }

    /**
     * 推送测试消息到队列
     */
    @POST
    @Path("/{queueName}/publish")
    @Operation(summary = "推送测试消息", description = "推送一条测试消息到指定队列")
    public Response publishTestMessage(
            @PathParam("queueName") String queueName,
            TestMessageRequest request) {

        try {
            ConnectionFactory factory = createConnectionFactory();
            try (Connection connection = factory.newConnection();
                 Channel channel = connection.createChannel()) {

                String exchange;
                String routingKey;
                Object task;
                String typeId;

                if ("ai-audit-tasks".equals(queueName)) {
                    if (request.commentId == null) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("success", false, "message", "审核队列需要 commentId")).build();
                    }
                    exchange = "ai-audit-tasks";
                    routingKey = "";
                    task = new com.biliwind.blog.service.ai.AiAuditTask(
                            request.commentId,
                            request.content != null ? request.content : "这是一条测试评论内容，包含一些关键词如 spam。"
                    );
                    typeId = com.biliwind.blog.service.ai.AiAuditTask.class.getName();
                } else {
                    if (request.postId == null) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("success", false, "message", "摘要队列需要 postId")).build();
                    }
                    exchange = "ai-tasks";
                    routingKey = "summary";
                    Map<String, String> content = new HashMap<>();
                    content.put("zh-cn", request.content != null ? request.content : "这是一篇测试文章的内容，用于生成 AI 摘要。");
                    task = new com.biliwind.blog.service.ai.AiSummaryTask(
                            request.postId,
                            content,
                            request.priority != null ? request.priority : 1,
                            0,
                            null
                    );
                    typeId = com.biliwind.blog.service.ai.AiSummaryTask.class.getName();
                }

                int rabbitPriority = 0;
                if (task instanceof com.biliwind.blog.service.ai.AiSummaryTask summaryTask) {
                    rabbitPriority = switch (summaryTask.priority()) {
                        case 0 -> 10;
                        case 1 -> 5;
                        default -> 0;
                    };
                }

                Map<String, Object> headers = new HashMap<>();
                headers.put("__TypeId__", typeId);

                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .priority(rabbitPriority)
                        .contentType("application/json")
                        .headers(headers)
                        .timestamp(Date.from(Instant.now()))
                        .build();

                String messageBody = objectMapper.writeValueAsString(task);
                channel.basicPublish(exchange, routingKey, props, messageBody.getBytes(StandardCharsets.UTF_8));

                return Response.ok(Map.of(
                        "success", true,
                        "message", "消息已成功发送到 " + queueName,
                        "data", Map.of(
                                "id", request.postId != null ? request.postId : request.commentId,
                                "queue", queueName
                        )
                )).build();
            }
        } catch (Exception e) {
            log.error("推送测试消息失败，queueName={}", queueName, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "success", false,
                            "message", "推送消息失败：" + SensitiveMessageSanitizer.sanitize(e.getMessage())
                    )).build();
        }
    }

    /**
     * 生成测试消息示例
     */
    @GET
    @Path("/generate-example")
    @Operation(summary = "生成消息示例", description = "生成一个可用于测试的消息示例")
    public Response generateExample() {
        Map<String, Object> summaryExample = new HashMap<>();
        summaryExample.put("postId", 1);
        summaryExample.put("priority", 1);
        summaryExample.put("content", "这是一篇测试文章的内容，用于测试 AI 摘要生成。");

        Map<String, Object> auditExample = new HashMap<>();
        auditExample.put("commentId", 1);
        auditExample.put("content", "这是一条包含敏感词的测试评论，用于测试 AI 审核功能。");

        return Response.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "summary", summaryExample,
                        "audit", auditExample
                ),
                "usage", "根据队列类型选择对应的参数。摘要队列使用 postId，审核队列使用 commentId。"
        )).build();
    }

    /**
     * Uses a dedicated short-lived channel per queue so a 404 NOT_FOUND on one
     * undeclared queue does not close the channel for other queues.
     */
    private RabbitQueueProbe probeQueuePassive(Connection connection, String queueName) {
        try (Channel channel = connection.createChannel()) {
            AMQP.Queue.DeclareOk ok = channel.queueDeclarePassive(queueName);
            return new RabbitQueueProbe(true, ok.getMessageCount(), ok.getConsumerCount(), null);
        } catch (Exception e) {
            String sanitized = SensitiveMessageSanitizer.sanitize(e.getMessage());
            if (sanitized != null && sanitized.contains("NOT_FOUND")) {
                return new RabbitQueueProbe(false, 0, 0, "队列尚未在 RabbitMQ 中声明");
            }
            return new RabbitQueueProbe(false, 0, 0, sanitized);
        }
    }

    private String resolveQueueHealth(
            boolean brokerConnected,
            boolean exists,
            int readyCount,
            int consumerCount,
            int activeCount,
            OutboxCounts outbox,
            long unprocessedDlq,
            boolean isDeadLetter) {
        if (!brokerConnected) {
            return "BROKER_OFFLINE";
        }
        if (!exists) {
            return "MISSING";
        }
        if (isDeadLetter) {
            return readyCount > 0 ? "BACKLOG" : "HEALTHY";
        }
        if (consumerCount == 0 && !"edge".equalsIgnoreCase(nodeRole)) {
            return "NO_CONSUMER";
        }
        if (outbox.failed > 0 || unprocessedDlq > 0 || readyCount > 20) {
            return "DEGRADED";
        }
        if (readyCount > 0 || activeCount > 0 || outbox.pending > 0 || outbox.inFlight > 0) {
            return "ACTIVE";
        }
        return "HEALTHY";
    }

    private Map<String, OutboxCounts> loadOutboxCountsByEventType() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select event_type, status, count(*) from outbox_events "
                                + "where status in ('PENDING', 'IN_FLIGHT', 'FAILED') "
                                + "group by event_type, status")
                .getResultList();
        Map<String, OutboxCounts> result = new HashMap<>();
        for (Object[] row : rows) {
            String eventType = row[0] == null ? "" : row[0].toString();
            String status = row[1] == null ? "" : row[1].toString();
            long count = row[2] instanceof Number n ? n.longValue() : 0L;
            OutboxCounts current = result.getOrDefault(eventType, OutboxCounts.EMPTY);
            result.put(eventType, switch (status) {
                case "PENDING" -> new OutboxCounts(count, current.inFlight, current.failed);
                case "IN_FLIGHT" -> new OutboxCounts(current.pending, count, current.failed);
                case "FAILED" -> new OutboxCounts(current.pending, current.inFlight, count);
                default -> current;
            });
        }
        return result;
    }

    private Map<String, Long> loadUnprocessedDeadLettersBySource() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select source_queue, count(*) from dead_letter_messages "
                                + "where is_processed = false group by source_queue")
                .getResultList();
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            String source = row[0] == null ? "" : row[0].toString();
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            result.put(source, count);
        }
        return result;
    }

    private Map<String, Object> buildSummary(
            boolean brokerConnected,
            String brokerError,
            Map<String, OutboxCounts> outboxByType) {
        long pending = 0;
        long inFlight = 0;
        long failed = 0;
        for (OutboxCounts counts : outboxByType.values()) {
            pending += counts.pending;
            inFlight += counts.inFlight;
            failed += counts.failed;
        }
        Number published24h = (Number) entityManager.createNativeQuery(
                        "select count(*) from outbox_events where status = 'PUBLISHED' and published_at >= :since")
                .setParameter("since", OffsetDateTime.now().minusHours(24))
                .getSingleResult();
        Number oldestPendingSec = (Number) entityManager.createNativeQuery(
                        "select coalesce(extract(epoch from (now() - min(created_at))), 0) "
                                + "from outbox_events where status in ('PENDING', 'IN_FLIGHT')")
                .getSingleResult();
        long unprocessedDeadLetters = DeadLetterMessage.count("isProcessed = false");
        long reviewingCommentsCount = Comment.count("deletedAt is null and isReviewing = true");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("brokerConnected", brokerConnected);
        summary.put("brokerHost", rabbitmqHost);
        summary.put("brokerPort", rabbitmqPort);
        summary.put("brokerError", brokerError);
        summary.put("nodeRole", nodeRole);
        summary.put("outboxEnabled", outboxEnabled);
        summary.put("outboxPending", pending);
        summary.put("outboxInFlight", inFlight);
        summary.put("outboxFailed", failed);
        summary.put("outboxPublished24h", published24h.longValue());
        summary.put("outboxOldestPendingSeconds", oldestPendingSec.longValue());
        summary.put("activeTaskCount", runtimeTracker.listActiveTasks().size());
        summary.put("unprocessedDeadLetters", unprocessedDeadLetters);
        summary.put("reviewingCommentsCount", reviewingCommentsCount);
        summary.put("checkedAt", OffsetDateTime.now());
        return summary;
    }

    private List<Map<String, Object>> loadRecentOutboxIssues(int limit) {
        List<OutboxEvent> events = OutboxEvent.find(
                "status in ('FAILED', 'IN_FLIGHT') or (status = 'PENDING' and attemptCount > 0) order by id desc")
                .page(0, limit)
                .list();
        List<Map<String, Object>> list = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.id);
            item.put("eventType", event.eventType);
            item.put("aggregateType", event.aggregateType);
            item.put("aggregateId", event.aggregateId);
            item.put("status", event.status);
            item.put("attemptCount", event.attemptCount);
            item.put("lastError", SensitiveMessageSanitizer.sanitize(event.lastError));
            item.put("availableAt", event.availableAt);
            item.put("createdAt", event.createdAt);
            list.add(item);
        }
        return list;
    }

    private ConnectionFactory createConnectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitmqHost);
        factory.setPort(rabbitmqPort);
        factory.setUsername(rabbitmqUsername);
        factory.setPassword(rabbitmqPassword);
        return factory;
    }

    private record RabbitQueueProbe(boolean exists, int messageCount, int consumerCount, String error) {
    }

    private record OutboxCounts(long pending, long inFlight, long failed) {
        static final OutboxCounts EMPTY = new OutboxCounts(0, 0, 0);
    }

    public static class TestMessageRequest {
        public Long postId;
        public Long commentId;
        public Integer priority;
        public String content;
    }
}
