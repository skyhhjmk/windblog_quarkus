package com.biliwind.blog.service.ai;

import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@ApplicationScoped
public class AiTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskProducer.class);

    @Inject
    @Channel("ai-summary-tasks")
    Emitter<AiSummaryTask> taskEmitter;

    @Inject
    @Channel("ai-audit-tasks")
    Emitter<AiAuditTask> auditEmitter;

    public void sendSummaryTask(AiSummaryTask task) {
        int rabbitPriority = convertToRabbitPriority(task.priority());

        OutgoingRabbitMQMetadata metadata = OutgoingRabbitMQMetadata.builder()
                .withPriority(rabbitPriority)
                .build();

        log.info("发送 AI 摘要任务到队列，postId={}, priority={}, retryCount={}",
                task.postId(), task.priority(), task.retryCount());

        Message<AiSummaryTask> msg = Message.of(task, Metadata.of(metadata));
        taskEmitter.send(msg);
    }

    public void sendSummaryTask(Long postId, Map<String, String> content, int priority, int retryCount, Long performedBy) {
        AiSummaryTask task = new AiSummaryTask(postId, content, priority, retryCount, performedBy);
        sendSummaryTask(task);
    }

    public void sendAuditTask(Long commentId, String content) {
        AiAuditTask task = new AiAuditTask(commentId, content);
        log.info("发送 AI 审核任务到队列，commentId={}", commentId);
        auditEmitter.send(task);
    }

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

    private int convertToRabbitPriority(int priority) {
        if (priority == 0) {
            return 10;
        } else if (priority == 1) {
            return 5;
        } else {
            return 0;
        }
    }
}
