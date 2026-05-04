package com.biliwind.blog.controller.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 队列监控 API
 * 用于查看 RabbitMQ 队列状态和推送测试消息
 */
@Path("/api/admin/queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "QueueMonitor", description = "队列监控管理")
public class AdminQueueMonitorApi {

    private static final Logger log = LoggerFactory.getLogger(AdminQueueMonitorApi.class);



    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "rabbitmq-host", defaultValue = "localhost")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port", defaultValue = "5672")
    int rabbitmqPort;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUsername;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    /**
     * 获取所有队列的基本信息
     */
    @GET
    @Operation(summary = "获取队列列表", description = "获取所有监控队列的基本信息，包括消息数量")
    public Response getQueues() {
        List<Map<String, Object>> queues = new ArrayList<>();
        
        try {
            ConnectionFactory factory = createConnectionFactory();
            try (Connection connection = factory.newConnection();
                 Channel channel = connection.createChannel()) {

                // 定义需要监控的队列及其描述
                Map<String, String> monitorQueues = new LinkedHashMap<>();
                monitorQueues.put("ai-summary-tasks", "AI 摘要任务队列");
                monitorQueues.put("ai-audit-tasks", "AI 评论审核任务队列");
                monitorQueues.put("ai-summary-dead-letter", "AI 摘要死信队列");
                monitorQueues.put("ai-audit-dead-letter", "AI 审核死信队列");

                for (Map.Entry<String, String> entry : monitorQueues.entrySet()) {
                    String name = entry.getKey();
                    String desc = entry.getValue();

                    try {
                        // 使用 active declare 确保队列存在，同时获取信息
                        // 注意：这里的参数必须与 application.properties 中的配置一致
                        AMQP.Queue.DeclareOk ok = channel.queueDeclare(name, true, false, false, null);

                        Map<String, Object> info = new HashMap<>();
                        info.put("name", name);
                        info.put("messageCount", ok.getMessageCount());
                        info.put("consumerCount", ok.getConsumerCount());
                        info.put("description", desc);
                        queues.add(info);
                    } catch (Exception e) {
                        log.warn("无法获取队列信息: {}, error: {}", name, e.getMessage());
                    }
                }
            }
            
            return Response.ok(Map.of(
                    "success", true,
                    "data", queues
            )).build();
            
        } catch (Exception e) {
            log.error("获取队列信息失败", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "success", false,
                            "message", "获取队列信息失败：" + e.getMessage()
                    )).build();
        }
    }

    /**
     * 获取单个队列的详细信息
     */
    @GET
    @Path("/{queueName}")
    @Operation(summary = "获取队列详情", description = "获取指定队列的详细信息")
    public Response getQueueDetail(@PathParam("queueName") String queueName) {
        try {
            ConnectionFactory factory = createConnectionFactory();
            try (Connection connection = factory.newConnection();
                 Channel channel = connection.createChannel()) {
                
                AMQP.Queue.DeclareOk queueInfo = channel.queueDeclarePassive(queueName);
                
                Map<String, Object> result = new HashMap<>();
                result.put("name", queueName);
                result.put("messageCount", queueInfo.getMessageCount());
                result.put("consumerCount", getConsumerCount(channel, queueName));
                result.put("isDurable", true);
                
                return Response.ok(Map.of(
                        "success", true,
                        "data", result
                )).build();
                
            }
        } catch (IOException | TimeoutException e) {
            log.error("获取队列详情失败，queueName={}", queueName, e);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(
                            "success", false,
                            "message", "队列不存在：" + queueName
                    )).build();
        } catch (Exception e) {
            log.error("获取队列详情失败，queueName={}", queueName, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "success", false,
                            "message", "获取队列详情失败：" + e.getMessage()
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
                    routingKey = ""; // fanout
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

                // 设置 RabbitMQ 优先级 (仅摘要任务支持优先级)
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
                channel.basicPublish(exchange, routingKey, props, messageBody.getBytes("UTF-8"));
                
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
                            "message", "推送消息失败：" + e.getMessage()
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
     * 创建 RabbitMQ 连接工厂
     */
    private ConnectionFactory createConnectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitmqHost);
        factory.setPort(rabbitmqPort);
        factory.setUsername(rabbitmqUsername);
        factory.setPassword(rabbitmqPassword);
        return factory;
    }

    /**
     * 获取队列的消费者数量
     */
    private int getConsumerCount(Channel channel, String queueName) {
        try {
            AMQP.Queue.DeclareOk ok = channel.queueDeclarePassive(queueName);
            return ok.getConsumerCount();
        } catch (Exception e) {
            return 0;
        }
    }



    /**
     * 测试消息请求体
     */
    public static class TestMessageRequest {
        public Long postId;
        public Long commentId;
        public Integer priority;
        public String content;
    }
}
