package com.biliwind.blog.service.storage;

import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class StorageSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(StorageSyncConsumer.class);

    @Inject
    StorageService storageService;

    @Inject
    @Channel("storage-sync-tasks")
    Emitter<StorageSyncMessage> syncEmitter;

    @Incoming("storage-sync-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @io.smallrye.reactive.messaging.annotations.Blocking
    public CompletionStage<Void> consumeSyncTask(Message<StorageSyncMessage> message) {
        StorageSyncMessage msg = message.getPayload();
        log.info("开始处理存储同步: mediaId={}, provider={}, variant={}, retry={}",
                msg.mediaId(), msg.providerName(), msg.variantType(), msg.retryCount());

        try {
            VariantType variant = VariantType.valueOf(msg.variantType().toUpperCase());
            SyncResult result = storageService.executeSync(
                    msg.mediaId(),
                    msg.providerName(),
                    variant,
                    msg.retryCount());

            if (result.success()) {
                log.info("存储同步成功: mediaId={}, provider={}, variant={}",
                        msg.mediaId(), msg.providerName(), msg.variantType());
                return message.ack();
            } else {
                return handleFailure(message, msg, result.errorMessage());
            }
        } catch (Exception e) {
            log.error("存储同步异常: mediaId={}, provider={}, variant={}",
                    msg.mediaId(), msg.providerName(), msg.variantType(), e);
            return handleFailure(message, msg, e.getMessage());
        }
    }

    private CompletionStage<Void> handleFailure(Message<StorageSyncMessage> message,
                                                StorageSyncMessage msg, String errorReason) {
        int nextRetryCount = msg.retryCount() + 1;
        if (nextRetryCount >= 3) {
            log.error("同步达到最大重试次数，进行 NACK 进入死信队列: mediaId={}, provider={}, variant={}",
                    msg.mediaId(), msg.providerName(), msg.variantType());
            return message.nack(new RuntimeException("Max retries reached: " + errorReason));
        } else {
            log.warn("同步失败，准备第 {} 次重试: mediaId={}, provider={}, reason={}",
                    nextRetryCount, msg.mediaId(), msg.providerName(), errorReason);
            StorageSyncMessage retryMsg = new StorageSyncMessage(
                    msg.mediaId(), msg.providerName(), msg.variantType(), nextRetryCount);
            syncEmitter.send(retryMsg);
            return message.ack();
        }
    }
}
