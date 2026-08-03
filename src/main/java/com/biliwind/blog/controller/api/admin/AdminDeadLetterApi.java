package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.DeadLetterMessage;
import com.biliwind.blog.service.ReliableAiTaskService;
import com.biliwind.blog.service.ReliableInfrastructureTaskService;
import com.biliwind.blog.service.ai.AiSummaryTask;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 死信消息管理 API
 * 用于查看和管理死信队列中的消息
 */
@Path("/api/admin/dead-letters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "DeadLetterManagement", description = "死信消息管理")
public class AdminDeadLetterApi {

    private static final Logger log = LoggerFactory.getLogger(AdminDeadLetterApi.class);

    @Inject
    ReliableAiTaskService reliableAiTaskService;

    @Inject
    ReliableInfrastructureTaskService reliableInfrastructureTaskService;


    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    /**
     * 获取所有死信消息
     */
    @GET
    @Operation(summary = "获取所有死信消息", description = "支持过滤已处理/未处理的消息")
    public Response getDeadLetters(
            @QueryParam("processed") Boolean processed,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        
        List<DeadLetterMessage> messages;
        
        if (processed == null) {
            messages = DeadLetterMessage.findAll(Sort.by("deadLetteredAt").descending())
                    .page(0, limit)
                    .list();
        } else {
            messages = DeadLetterMessage.find("isProcessed = ?1", 
                    Sort.by("deadLetteredAt").descending(), processed)
                    .page(0, limit)
                    .list();
        }
        
        List<Map<String, Object>> result = messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
        
        return Response.ok(Map.of(
                "success", true,
                "data", result,
                "total", DeadLetterMessage.count()
        )).build();
    }

    /**
     * 获取单个死信消息详情
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "获取死信消息详情")
    public Response getDeadLetter(@PathParam("id") Long id) {
        DeadLetterMessage message = DeadLetterMessage.findById(id);
        
        if (message == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "未找到该死信消息"))
                    .build();
        }
        
        return Response.ok(Map.of(
                "success", true,
                "data", convertToMap(message)
        )).build();
    }

    /**
     * 重试死信消息
     */
    @POST
    @Path("/{id}/retry")
    @Transactional
    @Operation(summary = "重试死信消息", description = "将死信消息重新发送到任务队列")
    public Response retryDeadLetter(@PathParam("id") Long id) {
        DeadLetterMessage message = DeadLetterMessage.findById(id);
        
        if (message == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "未找到该死信消息"))
                    .build();
        }
        
        if (message.isProcessed) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "该消息已处理过，无法重试"))
                    .build();
        }
        
        try {
            Map<String, Object> content = message.messageContent;
            if (content == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("success", false, "message", "消息内容为空"))
                        .build();
            }

            String queue = message.sourceQueue;
            if ("ai-summary-tasks".equals(queue)) {
                Long postId = ((Number) content.get("postId")).longValue();
                Integer priority = content.containsKey("priority") ?
                        ((Number) content.get("priority")).intValue() : 1;
                @SuppressWarnings("unchecked")
                Map<String, String> postContent = (Map<String, String>) content.get("content");

                Long userId = adminRequestContext.getUserId();
                AiSummaryTask task = new AiSummaryTask(postId, postContent, priority, userId);
                reliableAiTaskService.enqueueSummary(task);

                message.markAsProcessed("手动重试 AI 摘要 - 已重新发送");
                log.info("已重试 AI 死信消息，id={}, postId={}", id, postId);
            } else if ("storage-sync-tasks".equals(queue)) {
                Long mediaId = ((Number) content.get("mediaId")).longValue();
                String storageClassName = (String) content.get("storageClassName");
                if (storageClassName == null) {
                    storageClassName = (String) content.get("providerName");
                }
                String variantType = (String) content.get("variantType");

                com.biliwind.blog.service.storage.dto.StorageSyncMessage syncMsg =
                        new com.biliwind.blog.service.storage.dto.StorageSyncMessage(
                                mediaId, storageClassName, variantType, 0);
                reliableInfrastructureTaskService.enqueueStorageSync(syncMsg);

                message.markAsProcessed("手动重试存储同步 - 已重新发送");
                log.info("已重试存储同步死信消息，id={}, mediaId={}", id, mediaId);
            } else if ("es-sync-tasks".equals(queue)) {
                Long postId = ((Number) content.get("postId")).longValue();
                String actionType = (String) content.get("actionType");

                com.biliwind.blog.service.elasticsearch.EsSyncTask esTask =
                        new com.biliwind.blog.service.elasticsearch.EsSyncTask(postId, actionType);
                reliableInfrastructureTaskService.enqueueEsSync(esTask);

                message.markAsProcessed("手动重试 ES 同步 - 已重新发送");
                log.info("已重试 ES 同步死信消息，id={}, postId={}", id, postId);
            } else {
                // 后备后向兼容分支 (处理没有填充 sourceQueue 的老死信)
                if (content.containsKey("mediaId")) {
                    Long mediaId = ((Number) content.get("mediaId")).longValue();
                    String storageClassName = (String) content.get("storageClassName");
                    if (storageClassName == null) {
                        storageClassName = (String) content.get("providerName");
                    }
                    String variantType = (String) content.get("variantType");

                    com.biliwind.blog.service.storage.dto.StorageSyncMessage syncMsg =
                            new com.biliwind.blog.service.storage.dto.StorageSyncMessage(
                                    mediaId, storageClassName, variantType, 0);
                    reliableInfrastructureTaskService.enqueueStorageSync(syncMsg);

                    message.markAsProcessed("手动重试存储同步(兼容) - 已重新发送");
                } else if (content.containsKey("postId") && content.containsKey("content")) {
                    Long postId = ((Number) content.get("postId")).longValue();
                    Integer priority = content.containsKey("priority") ?
                            ((Number) content.get("priority")).intValue() : 1;
                    @SuppressWarnings("unchecked")
                    Map<String, String> postContent = (Map<String, String>) content.get("content");

                    Long userId = adminRequestContext.getUserId();
                    AiSummaryTask task = new AiSummaryTask(postId, postContent, priority, userId);
                    reliableAiTaskService.enqueueSummary(task);

                    message.markAsProcessed("手动重试 AI 摘要(兼容) - 已重新发送");
                } else if (content.containsKey("postId") && content.containsKey("actionType")) {
                    Long postId = ((Number) content.get("postId")).longValue();
                    String actionType = (String) content.get("actionType");

                    com.biliwind.blog.service.elasticsearch.EsSyncTask esTask =
                            new com.biliwind.blog.service.elasticsearch.EsSyncTask(postId, actionType);
                    reliableInfrastructureTaskService.enqueueEsSync(esTask);

                    message.markAsProcessed("手动重试 ES 同步(兼容) - 已重新发送");
                } else {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("success", false, "message", "无法识别的死信消息队列类型"))
                            .build();
                }
            }
            
            return Response.ok(Map.of(
                    "success", true,
                    "message", "消息已重新发送到任务队列"
            )).build();
            
        } catch (Exception e) {
            log.error("重试死信消息失败，id={}", id, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "message", "重试失败：" + SensitiveMessageSanitizer.sanitize(e.getMessage())))
                    .build();
        }
    }

    /**
     * 批量重试死信消息
     */
    @POST
    @Path("/retry-batch")
    @Transactional
    @Operation(summary = "批量重试死信消息", description = "重试所有未处理的死信消息")
    public Response retryBatch(@QueryParam("limit") @DefaultValue("10") int limit) {
        List<DeadLetterMessage> unprocessed = DeadLetterMessage.find("isProcessed = false", Sort.by("deadLetteredAt").ascending())
                .page(0, limit)
                .list();
        
        int successCount = 0;
        int failCount = 0;
        
        for (DeadLetterMessage message : unprocessed) {
            try {
                Map<String, Object> content = message.messageContent;
                if (content == null) {
                    continue;
                }

                String queue = message.sourceQueue;
                if ("ai-summary-tasks".equals(queue)) {
                    Long postId = ((Number) content.get("postId")).longValue();
                    Integer priority = content.containsKey("priority") ? 
                            ((Number) content.get("priority")).intValue() : 1;
                    @SuppressWarnings("unchecked")
                    Map<String, String> postContent = (Map<String, String>) content.get("content");

                    Long userId = adminRequestContext.getUserId();
                    AiSummaryTask task = new AiSummaryTask(postId, postContent, priority, userId);
                    reliableAiTaskService.enqueueSummary(task);

                    message.markAsProcessed("批量重试 AI - 已重新发送");
                    successCount = successCount + 1;
                } else if ("storage-sync-tasks".equals(queue)) {
                    Long mediaId = ((Number) content.get("mediaId")).longValue();
                    String storageClassName = (String) content.get("storageClassName");
                    if (storageClassName == null) {
                        storageClassName = (String) content.get("providerName");
                    }
                    String variantType = (String) content.get("variantType");

                    com.biliwind.blog.service.storage.dto.StorageSyncMessage syncMsg =
                            new com.biliwind.blog.service.storage.dto.StorageSyncMessage(
                                    mediaId, storageClassName, variantType, 0);
                    reliableInfrastructureTaskService.enqueueStorageSync(syncMsg);

                    message.markAsProcessed("批量重试同步 - 已重新发送");
                    successCount = successCount + 1;
                } else if ("es-sync-tasks".equals(queue)) {
                    Long postId = ((Number) content.get("postId")).longValue();
                    String actionType = (String) content.get("actionType");

                    com.biliwind.blog.service.elasticsearch.EsSyncTask esTask =
                            new com.biliwind.blog.service.elasticsearch.EsSyncTask(postId, actionType);
                    reliableInfrastructureTaskService.enqueueEsSync(esTask);

                    message.markAsProcessed("批量重试 ES 同步 - 已重新发送");
                    successCount = successCount + 1;
                } else {
                    // 后备后向兼容分支
                    if (content.containsKey("mediaId")) {
                        Long mediaId = ((Number) content.get("mediaId")).longValue();
                        String storageClassName = (String) content.get("storageClassName");
                        if (storageClassName == null) {
                            storageClassName = (String) content.get("providerName");
                        }
                        String variantType = (String) content.get("variantType");

                        com.biliwind.blog.service.storage.dto.StorageSyncMessage syncMsg =
                                new com.biliwind.blog.service.storage.dto.StorageSyncMessage(
                                        mediaId, storageClassName, variantType, 0);
                        reliableInfrastructureTaskService.enqueueStorageSync(syncMsg);

                        message.markAsProcessed("批量重试同步(兼容) - 已重新发送");
                        successCount = successCount + 1;
                    } else if (content.containsKey("postId") && content.containsKey("content")) {
                        Long postId = ((Number) content.get("postId")).longValue();
                        Integer priority = content.containsKey("priority") ?
                                ((Number) content.get("priority")).intValue() : 1;
                        @SuppressWarnings("unchecked")
                        Map<String, String> postContent = (Map<String, String>) content.get("content");

                        Long userId = adminRequestContext.getUserId();
                        AiSummaryTask task = new AiSummaryTask(postId, postContent, priority, userId);
                        reliableAiTaskService.enqueueSummary(task);

                        message.markAsProcessed("批量重试 AI(兼容) - 已重新发送");
                        successCount = successCount + 1;
                    } else if (content.containsKey("postId") && content.containsKey("actionType")) {
                        Long postId = ((Number) content.get("postId")).longValue();
                        String actionType = (String) content.get("actionType");

                        com.biliwind.blog.service.elasticsearch.EsSyncTask esTask =
                                new com.biliwind.blog.service.elasticsearch.EsSyncTask(postId, actionType);
                        reliableInfrastructureTaskService.enqueueEsSync(esTask);

                        message.markAsProcessed("批量重试 ES(兼容) - 已重新发送");
                        successCount = successCount + 1;
                    } else {
                        message.markAsProcessed("批量重试跳过 - 未知消息类型");
                        failCount = failCount + 1;
                    }
                }
            } catch (Exception e) {
                log.error("批量重试失败，id={}", message.id, e);
                failCount = failCount + 1;
            }
        }
        
        log.info("批量重试完成，成功={}, 失败={}", successCount, failCount);
        
        return Response.ok(Map.of(
                "success", true,
                "message", "批量重试完成",
                "successCount", successCount,
                "failCount", failCount
        )).build();
    }

    /**
     * 标记死信消息为已处理
     */
    @POST
    @Path("/{id}/dismiss")
    @Transactional
    @Operation(summary = "标记为已处理", description = "不重试，直接标记为已处理")
    public Response dismiss(@PathParam("id") Long id, 
                           @QueryParam("note") String note) {
        DeadLetterMessage message = DeadLetterMessage.findById(id);
        
        if (message == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "未找到该死信消息"))
                    .build();
        }
        
        message.markAsProcessed(note != null ? note : "手动标记为已处理");
        
        log.info("已标记死信消息为已处理，id={}", id);
        
        return Response.ok(Map.of(
                "success", true,
                "message", "已标记为已处理"
        )).build();
    }

    /**
     * 删除死信消息记录
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "删除死信消息记录")
    public Response delete(@PathParam("id") Long id) {
        DeadLetterMessage message = DeadLetterMessage.findById(id);
        
        if (message == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "未找到该死信消息"))
                    .build();
        }
        
        message.delete();
        
        log.info("已删除死信消息记录，id={}", id);
        
        return Response.ok(Map.of(
                "success", true,
                "message", "删除成功"
        )).build();
    }

    /**
     * 获取统计信息
     */
    @GET
    @Path("/stats")
    @Operation(summary = "获取统计信息")
    public Response getStats() {
        long total = DeadLetterMessage.count();
        long unprocessed = DeadLetterMessage.count("isProcessed = false");
        long processed = DeadLetterMessage.count("isProcessed = true");
        
        return Response.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "total", total,
                        "unprocessed", unprocessed,
                        "processed", processed
                )
        )).build();
    }

    /**
     * 将 DeadLetterMessage 转换为 Map
     */
    private Map<String, Object> convertToMap(DeadLetterMessage message) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", message.id);
        map.put("sourceQueue", message.sourceQueue);
        map.put("exchangeName", message.exchangeName);
        map.put("routingKey", message.routingKey);
        map.put("postId", message.postId);
        map.put("priority", message.priority);
        map.put("retryCount", message.retryCount);
        map.put("errorReason", message.errorReason);
        map.put("deadLetteredAt", message.deadLetteredAt);
        map.put("isProcessed", message.isProcessed);
        map.put("processedAt", message.processedAt);
        map.put("processNote", message.processNote);
        map.put("createdAt", message.createdAt);
        return map;
    }
}
