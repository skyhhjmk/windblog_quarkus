package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.DeadLetterMessage;
import com.biliwind.blog.service.ai.AiSummaryTask;
import com.biliwind.blog.service.ai.AiTaskProducer;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import com.biliwind.blog.service.elasticsearch.EsSyncTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/storage/dead-letter")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminDeadLetterController {

    private static final Logger log = Logger.getLogger(AdminDeadLetterController.class);

    @Inject
    AiTaskProducer aiTaskProducer;

    @Inject
    @Channel("storage-sync-tasks")
    Instance<Emitter<StorageSyncMessage>> syncEmitter;

    @Inject
    @Channel("es-sync-tasks")
    Instance<Emitter<EsSyncTask>> esSyncEmitter;

    @GET
    public Response listMessages(
            @DefaultValue("1") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("pageSize") int pageSize) {
        List<DeadLetterMessage> messages = DeadLetterMessage.findAll()
                .page(page - 1, pageSize)
                .list();

        ArrayList<DeadLetterResponse> result = new ArrayList<>();
        for (DeadLetterMessage message : messages) {
            DeadLetterResponse response = DeadLetterResponse.fromEntity(message);
            result.add(response);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("items", result);
        return Response.ok(body).build();
    }

    @POST
    @Path("/{id}/retry")
    @Transactional
    public Response retryMessage(@PathParam("id") Long id) {
        DeadLetterMessage message = DeadLetterMessage.findById(id);
        if (message == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (message.isProcessed) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            Map<String, Object> content = message.messageContent;
            if (content == null) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            String queue = message.sourceQueue;
            if ("ai-summary-tasks".equals(queue)) {
                Long postId = ((Number) content.get("postId")).longValue();
                Integer priority = content.containsKey("priority") ?
                        ((Number) content.get("priority")).intValue() : 1;
                @SuppressWarnings("unchecked")
                Map<String, String> postContent = (Map<String, String>) content.get("content");

                Long userId = 1L; 
                AiSummaryTask task = new AiSummaryTask(postId, postContent, priority, userId);
                aiTaskProducer.sendSummaryTask(task);

                message.markAsProcessed("通过兼容接口手动重试 AI 摘要 - 已重新发送");
                log.info("已通过兼容接口重试 AI 死信消息，id=" + id);
            } else if ("storage-sync-tasks".equals(queue)) {
                Long mediaId = ((Number) content.get("mediaId")).longValue();
                String storageClassName = (String) content.get("storageClassName");
                if (storageClassName == null) {
                    storageClassName = (String) content.get("providerName");
                }
                String variantType = (String) content.get("variantType");

                StorageSyncMessage syncMsg = new StorageSyncMessage(mediaId, storageClassName, variantType, 0);
                syncEmitter.get().send(syncMsg);

                message.markAsProcessed("通过兼容接口手动重试存储同步 - 已重新发送");
                log.info("已通过兼容接口重试存储同步死信消息，id=" + id);
            } else if ("es-sync-tasks".equals(queue)) {
                Long postId = ((Number) content.get("postId")).longValue();
                String actionType = (String) content.get("actionType");

                EsSyncTask esTask = new EsSyncTask(postId, actionType);
                esSyncEmitter.get().send(esTask);

                message.markAsProcessed("通过兼容接口手动重试 ES 同步 - 已重新发送");
                log.info("已通过兼容接口重试 ES 同步死信消息，id=" + id);
            } else {
                // 兼容老数据重试分支
                if (content.containsKey("mediaId")) {
                    Long mediaId = ((Number) content.get("mediaId")).longValue();
                    String storageClassName = (String) content.get("storageClassName");
                    if (storageClassName == null) {
                        storageClassName = (String) content.get("providerName");
                    }
                    String variantType = (String) content.get("variantType");

                    StorageSyncMessage syncMsg = new StorageSyncMessage(mediaId, storageClassName, variantType, 0);
                    syncEmitter.get().send(syncMsg);

                    message.markAsProcessed("通过兼容接口手动重试存储同步(兼容) - 已重新发送");
                } else if (content.containsKey("postId") && content.containsKey("content")) {
                    Long postId = ((Number) content.get("postId")).longValue();
                    Integer priority = content.containsKey("priority") ?
                            ((Number) content.get("priority")).intValue() : 1;
                    @SuppressWarnings("unchecked")
                    Map<String, String> postContent = (Map<String, String>) content.get("content");

                    Long userId = 1L;
                    AiSummaryTask task = new AiSummaryTask(postId, postContent, priority, userId);
                    aiTaskProducer.sendSummaryTask(task);

                    message.markAsProcessed("通过兼容接口手动重试 AI 摘要(兼容) - 已重新发送");
                } else if (content.containsKey("postId") && content.containsKey("actionType")) {
                    Long postId = ((Number) content.get("postId")).longValue();
                    String actionType = (String) content.get("actionType");

                    EsSyncTask esTask = new EsSyncTask(postId, actionType);
                    esSyncEmitter.get().send(esTask);

                    message.markAsProcessed("通过兼容接口手动重试 ES 同步(兼容) - 已重新发送");
                } else {
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }

            return Response.noContent().build();
        } catch (Exception e) {
            log.error("重试死信消息失败，id=" + id, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteMessage(@PathParam("id") Long id) {
        DeadLetterMessage message = DeadLetterMessage.findById(id);
        if (message == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        message.delete();
        return Response.noContent().build();
    }

    public record DeadLetterResponse(
            Long id,
            String sourceQueue,
            String exchangeName,
            String routingKey,
            Long postId,
            Integer retryCount,
            String errorReason,
            Map<String, Object> messageContent,
            Instant deadLetteredAt,
            boolean isProcessed,
            Instant processedAt,
            String processNote,
            String statusText) {

        public static DeadLetterResponse fromEntity(DeadLetterMessage entity) {
            String statusText = "待处理";
            if (entity.isProcessed) {
                statusText = "已处理";
            }
            return new DeadLetterResponse(
                    entity.id,
                    entity.sourceQueue,
                    entity.exchangeName,
                    entity.routingKey,
                    entity.postId,
                    entity.retryCount,
                    entity.errorReason,
                    entity.messageContent,
                    entity.deadLetteredAt,
                    entity.isProcessed,
                    entity.processedAt,
                    entity.processNote,
                    statusText);
        }
    }
}
