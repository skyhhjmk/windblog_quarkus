package com.biliwind.blog.service.storage;

import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class StorageSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(StorageSyncConsumer.class);
    private static final int MAX_RETRY_COUNT = 3;
    private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    @Inject
    StorageService storageService;

    @Inject
    @Channel("storage-sync-tasks")
    Emitter<StorageSyncMessage> syncEmitter;

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

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
        CompletableFuture<Void> retryFuture = new CompletableFuture<>();
        RETRY_SCHEDULER.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    syncEmitter.send(retryMessage);
                    message.ack().whenComplete((ignoredResult, ackError) -> {
                        if (ackError != null) {
                            retryFuture.completeExceptionally(ackError);
                        } else {
                            retryFuture.complete(null);
                        }
                    });
                } catch (Exception e) {
                    message.nack(e);
                    retryFuture.completeExceptionally(e);
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
        return retryFuture;
    }
}
