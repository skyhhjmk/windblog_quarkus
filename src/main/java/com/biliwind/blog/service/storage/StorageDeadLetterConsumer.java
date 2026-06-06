package com.biliwind.blog.service.storage;

import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class StorageDeadLetterConsumer {

    @Inject
    StorageService storageService;

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    @Incoming("storage-sync-dlq")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @jakarta.transaction.Transactional
    public CompletionStage<Void> consumeDeadLetter(Message<StorageSyncMessage> message) {
        if (nodeRoleService.isEdgeNode()) {
            return message.ack();
        }
        StorageSyncMessage msg = message.getPayload();
        Log.warn("死信队列收到失败的同步任务: mediaId=" + msg.mediaId()
                + ", provider=" + msg.storageClassName()
                + ", variant=" + msg.variantType()
                + ", retryCount=" + msg.retryCount());

        try {
            persistDeadLetterMessage(msg);
            VariantType variant = VariantType.valueOf(msg.variantType().toUpperCase());
            storageService.updateVariantStatus(
                    msg.mediaId(), msg.storageClassName(), variant, "failed", null, null, 0);
            return message.ack();
        } catch (Exception e) {
            Log.error("持久化死信消息失败", e);
            return message.nack(e);
        }
    }

    private void persistDeadLetterMessage(StorageSyncMessage msg) {
        com.biliwind.blog.model.DeadLetterMessage deadLetterMessage = new com.biliwind.blog.model.DeadLetterMessage();
        deadLetterMessage.sourceQueue = "storage-sync-tasks";
        deadLetterMessage.exchangeName = "storage-sync-dlx";
        deadLetterMessage.routingKey = "storage-sync-dead";
        deadLetterMessage.retryCount = msg.retryCount();
        deadLetterMessage.errorReason = "存储同步失败，达到最大重试次数";

        Map<String, Object> content = new HashMap<>();
        content.put("mediaId", msg.mediaId());
        content.put("storageClassName", msg.storageClassName());
        content.put("variantType", msg.variantType());
        content.put("retryCount", msg.retryCount());
        deadLetterMessage.messageContent = content;
        deadLetterMessage.deadLetteredAt = Instant.now();
        deadLetterMessage.persist();
        Log.info("死信消息已持久化到数据库, id=" + deadLetterMessage.id);
    }
}
