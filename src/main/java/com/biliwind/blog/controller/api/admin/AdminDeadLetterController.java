package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.DeadLetterMessage;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

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

    @Inject
    @Channel("storage-sync-tasks")
    Emitter<StorageSyncMessage> syncEmitter;

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

        Long mediaId = message.postId;
        String sourceQueue = message.sourceQueue;
        String variant = "original";
        if (message.messageContent != null) {
            Object variantObj = message.messageContent.get("variant");
            if (variantObj != null) {
                variant = variantObj.toString();
            }
        }

        StorageSyncMessage syncMessage = new StorageSyncMessage(
                mediaId,
                sourceQueue,
                variant,
                0);

        syncEmitter.send(syncMessage);

        message.isProcessed = true;
        message.processNote = "手动重试";
        message.persist();

        return Response.noContent().build();
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
