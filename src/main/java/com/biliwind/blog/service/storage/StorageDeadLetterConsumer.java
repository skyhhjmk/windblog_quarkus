package com.biliwind.blog.service.storage;

import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class StorageDeadLetterConsumer {

    @Inject
    StorageService storageService;

    @Incoming("storage-sync-dlq")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public CompletionStage<Void> consumeDeadLetter(Message<StorageSyncMessage> message) {
        StorageSyncMessage msg = message.getPayload();
        Log.warn("死信队列收到失败的同步任务: mediaId=" + msg.mediaId()
                + ", provider=" + msg.providerName()
                + ", variant=" + msg.variantType()
                + ", retryCount=" + msg.retryCount());

        VariantType variant = VariantType.valueOf(msg.variantType().toUpperCase());
        storageService.updateVariantStatus(
                msg.mediaId(), msg.providerName(), variant, "failed", null, null, 0);

        message.ack();
        return CompletableFuture.completedStage(null);
    }
}
