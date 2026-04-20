package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.ai.AiSummaryTask;
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
                
                // 获取 ai-summary-tasks 队列信息
                AMQP.Queue.DeclareOk taskQueue = channel.queueDeclarePassive("ai-summary-tasks");
                Map<String, Object> taskQueueInfo = new HashMap<>();
                taskQueueInfo.put("name", "ai-summary-tasks");
                taskQueueInfo.put("messageCount", taskQueue.getMessageCount());
                taskQueueInfo.put("consumerCount", getConsumerCount(channel, "ai-summary-tasks"));
                taskQueueInfo.put("description", "AI 摘要任务队列");
                queues.add(taskQueueInfo);
                
                // 获取死信队列信息
                AMQP.Queue.DeclareOk dlxQueue = channel.queueDeclarePassive("ai-summary-dead-letter");
                Map<String, Object> dlxQueueInfo = new HashMap<>();
                dlxQueueInfo.put("name", "ai-summary-dead-letter");
                dlxQueueInfo.put("messageCount", dlxQueue.getMessageCount());
                dlxQueueInfo.put("consumerCount", getConsumerCount(channel, "ai-summary-dead-letter"));
                dlxQueueInfo.put("description", "死信队列");
                queues.add(dlxQueueInfo);
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
        
        if (request == null || request.postId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "postId 是必填参数"
                    )).build();
        }
        
        try {
            ConnectionFactory factory = createConnectionFactory();
            try (Connection connection = factory.newConnection();
                 Channel channel = connection.createChannel()) {
                
                // 构建测试任务
                Map<String, String> content = new HashMap<>();
                content.put("zh-cn", request.content != null ? request.content : "测试文章内容");

                int priorityValue = 1;
                if (request.priority != null) {
                    priorityValue = request.priority;
                }
                
                AiSummaryTask task = new AiSummaryTask(
                        request.postId,
                        content,
                        priorityValue,
                        0,
                        null
                );
                
                // 设置 RabbitMQ 优先级
                int rabbitPriority = switch (task.priority()) {
                    case 0 -> 10;
                    case 1 -> 5;
                    default -> 0;
                };

                // 解决缺少类型导致的消费者反序列化崩溃问题
                Map<String, Object> headers = new HashMap<>();
                headers.put("__TypeId__", AiSummaryTask.class.getName());
                
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .priority(rabbitPriority)
                        .contentType("application/json")
                        .headers(headers)
                        .timestamp(Date.from(Instant.now()))
                        .build();
                
                // 使用 Jackson 序列化任务为 JSON
                String messageBody = objectMapper.writeValueAsString(task);
                
                channel.basicPublish("ai-tasks", "summary", props, messageBody.getBytes("UTF-8"));
                
                log.info("测试消息已发送到队列，queueName={}, postId={}, priority={}", 
                        queueName, request.postId, request.priority);
                
                return Response.ok(Map.of(
                        "success", true,
                        "message", "消息已成功发送到队列",
                        "data", Map.of(
                                "postId", request.postId,
                                "priority", request.priority,
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
        Map<String, Object> example = new HashMap<>();
        example.put("postId", 1);
        example.put("priority", 1);
        example.put("content", "这是一篇测试文章的摘要内容，用于测试消息队列功能。");
        example.put("description", "AI 摘要任务示例");
        example.put("usage", "将此示例的 postId 和 content 修改后，通过 /api/admin/queues/{queueName}/publish 接口推送");
        
        return Response.ok(Map.of(
                "success", true,
                "data", example
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
        public Integer priority;
        public String content;
    }
}
