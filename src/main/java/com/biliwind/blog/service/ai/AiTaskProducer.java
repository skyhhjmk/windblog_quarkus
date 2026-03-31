package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 摘要任务生产者，发送 AI 摘要生成任务到 RabbitMQ
 */
@ApplicationScoped
public class AiTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskProducer.class);

    @Inject
    @Channel("ai-summary-tasks")
    Emitter<AiSummaryTask> taskEmitter;

    /**
     * 发送 AI 摘要任务到消息队列
     * 
     * @param task AI 摘要任务
     */
    public void sendSummaryTask(AiSummaryTask task) {
        // Set RabbitMQ priority if possible (RabbitMQ supports 0-255, usually 0-10)
        // Convert our 0(High)-2(Low) to RabbitMQ priorities (e.g., 10, 5, 0)
        int rabbitPriority = switch (task.priority()) {
            case 0 -> 10;
            case 1 -> 5;
            default -> 0;
        };

        OutgoingRabbitMQMetadata metadata = OutgoingRabbitMQMetadata.builder()
                .withPriority(rabbitPriority)
                .build();

        log.info("发送 AI 摘要任务到队列，postId={}, priority={}, retryCount={}", 
                task.postId(), task.priority(), task.retryCount());

        taskEmitter.send(org.eclipse.microprofile.reactive.messaging.Message.of(task, Metadata.of(metadata)));
    }

    /**
     * 重新发送失败的任务（用于死信队列重试）
     * 
     * @param originalTask 原始任务
     * @return 是否发送成功
     */
    public boolean resendFailedTask(AiSummaryTask originalTask) {
        try {
            AiSummaryTask retryTask = originalTask.withRetry();
            sendSummaryTask(retryTask);
            log.info("已重新发送失败任务，postId={}, 当前重试次数={}", 
                    originalTask.postId(), retryTask.retryCount());
            return true;
        } catch (Exception e) {
            log.error("重新发送任务失败，postId={}", originalTask.postId(), e);
            return false;
        }
    }
}
