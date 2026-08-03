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
    private static final int MAX_RETRY_COUNT = 3;
    @Inject
    StorageService storageService;

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    @Inject
    com.biliwind.blog.service.ReliableInfrastructureTaskService reliableInfrastructureTaskService;

    @Incoming("storage-sync-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @io.smallrye.reactive.messaging.annotations.Blocking
    public CompletionStage<Void> consumeSyncTask(Message<StorageSyncMessage> message) {
        if (nodeRoleService.isEdgeNode()) {
            log.info("从节点跳过存储同步消费");
            return message.ack();
        }
        StorageSyncMessage msg = message.getPayload();
        log.info("开始处理存储同步: mediaId={}, provider={}, variant={}, retry={}",
                msg.mediaId(), msg.storageClassName(), msg.variantType(), msg.retryCount());

        try {
            VariantType variant = VariantType.valueOf(msg.variantType().toUpperCase());
            SyncResult result = storageService.executeSync(
                    msg.mediaId(),
                    msg.storageClassName(),
                    variant,
                    msg.retryCount());

            if (result.success()) {
                log.info("存储同步成功: mediaId={}, provider={}, variant={}",
                        msg.mediaId(), msg.storageClassName(), msg.variantType());
                return message.ack();
            } else {
                return handleFailure(message, msg, result.errorMessage());
            }
        } catch (Exception e) {
            log.error("存储同步异常: mediaId={}, provider={}, variant={}",
                    msg.mediaId(), msg.storageClassName(), msg.variantType(), e);
            return handleFailure(message, msg, e.getMessage());
        }
    }

    private CompletionStage<Void> handleFailure(Message<StorageSyncMessage> message,
                                                StorageSyncMessage msg, String errorReason) {
        int nextRetryCount = msg.retryCount() + 1;
        if (nextRetryCount >= MAX_RETRY_COUNT) {
            log.error("同步达到最大重试次数，进行 NACK 进入死信队列: mediaId={}, provider={}, variant={}",
                    msg.mediaId(), msg.storageClassName(), msg.variantType());
            return message.nack(new RuntimeException("Max retries reached: " + errorReason));
        } else {
            long delaySeconds = calculateRetryDelaySeconds(nextRetryCount);
            log.warn("同步失败，{} 秒后准备第 {} 次重试: mediaId={}, provider={}, reason={}",
                    delaySeconds, nextRetryCount, msg.mediaId(), msg.storageClassName(), errorReason);
            StorageSyncMessage retryMsg = new StorageSyncMessage(
                    msg.mediaId(), msg.storageClassName(), msg.variantType(), nextRetryCount);
            return sendRetryAfterDelay(message, retryMsg, delaySeconds);
        }
    }

    private long calculateRetryDelaySeconds(int retryCount) {
        long delaySeconds = 5;
        for (int index = 1; index < retryCount; index++) {
            delaySeconds = delaySeconds * 2;
        }
        return delaySeconds;
    }

    private CompletionStage<Void> sendRetryAfterDelay(Message<StorageSyncMessage> message,
                                                      StorageSyncMessage retryMessage,
                                                      long delaySeconds) {
        try {
            reliableInfrastructureTaskService.enqueueStorageSync(
                    retryMessage, java.time.Duration.ofSeconds(Math.max(1L, delaySeconds)));
            return message.ack();
        } catch (Exception exception) {
            return message.nack(exception);
        }
    }
}
