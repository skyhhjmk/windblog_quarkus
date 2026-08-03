package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.DeadLetterMessage;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);
    private static final String MQ_TAG = "[MQ]";
    private static final String ERROR_MARK = "!!!!";
    private static final String WARN_MARK = "----";

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    private void logMqWarn(String marker, String message, Object... args) {
        log.warn("{} {} {}", MQ_TAG, marker, String.format(message, args));
    }

    private void logMqError(String marker, String message, Object... args) {
        log.error("{} {} {}", MQ_TAG, marker, String.format(message, args));
    }


    @Incoming("ai-summary-dead-letter-in")
    @Transactional
    public CompletionStage<Void> consume(Message<JsonObject> message) {
        if (nodeRoleService.isEdgeNode()) {
            return message.ack().toCompletableFuture();
        }
        AiSummaryTask task;
        try {
            task = message.getPayload().mapTo(AiSummaryTask.class);
        } catch (Exception e) {
            logMqError(ERROR_MARK, "死信JsonObject反序列化失败: %s",
                    SensitiveMessageSanitizer.sanitize(e.getMessage()));
            return message.ack();
        }

        logMqWarn(WARN_MARK, "收到死信消息，postId=%d, priority=%d, retryCount=%d",
                task.postId(), task.priority(), task.retryCount());

        try {
            saveDeadLetterMessage(task);

            int contentLanguageCount = 0;
            if (task.content() != null) {
                contentLanguageCount = task.content().size();
            }
            logMqWarn(WARN_MARK, "死信消息详情，postId=%d, priority=%d, 重试次数=%d, 任务内容语言数=%d",
                    task.postId(), task.priority(), task.retryCount(), contentLanguageCount);

            logMqWarn(WARN_MARK, "死信消息已处理，postId=%d", task.postId());
            return message.ack().toCompletableFuture();
        } catch (Exception e) {
            logMqError(ERROR_MARK, "处理死信消息时发生异常，postId=%d, error=%s", task.postId(),
                    SensitiveMessageSanitizer.sanitize(e.getMessage()), e);
            return message.nack(e);
        }
    }

    @Incoming("ai-audit-dead-letter-in")
    @Transactional
    public CompletionStage<Void> consumeAuditDeadLetter(Message<JsonObject> message) {
        if (nodeRoleService.isEdgeNode()) {
            return message.ack().toCompletableFuture();
        }

        AiAuditTask task;
        try {
            task = message.getPayload().mapTo(AiAuditTask.class);
        } catch (Exception e) {
            logMqError(ERROR_MARK, "审核死信JsonObject反序列化失败: %s",
                    SensitiveMessageSanitizer.sanitize(e.getMessage()));
            return message.ack();
        }

        logMqWarn(WARN_MARK, "收到审核死信消息，commentId=%d, retryCount=%d",
                task.commentId(), task.retryCount());

        try {
            saveAuditDeadLetterMessage(task);

            int contentLength = 0;
            if (task.content() != null) {
                contentLength = task.content().length();
            }
            logMqWarn(WARN_MARK, "审核死信消息详情，commentId=%d, 重试次数=%d, 内容长度=%d",
                    task.commentId(), task.retryCount(), contentLength);

            logMqWarn(WARN_MARK, "审核死信消息已处理，commentId=%d", task.commentId());
            return message.ack().toCompletableFuture();
        } catch (Exception e) {
            logMqError(ERROR_MARK, "处理审核死信消息时发生异常，commentId=%d, error=%s",
                    task.commentId(), SensitiveMessageSanitizer.sanitize(e.getMessage()), e);
            return message.nack(e);
        }
    }

    private void saveDeadLetterMessage(AiSummaryTask task) {
        DeadLetterMessage dlm = new DeadLetterMessage();
        dlm.sourceQueue = "ai-summary-tasks";
        dlm.exchangeName = "ai-tasks-dlx";
        dlm.routingKey = "summary-dead";
        dlm.postId = task.postId();
        dlm.priority = task.priority();
        dlm.retryCount = task.retryCount();
        dlm.errorReason = "AI 摘要处理失败（超过最大重试次数或处理超时）";

        Map<String, Object> content = new HashMap<>();
        content.put("postId", task.postId());
        content.put("priority", task.priority());
        content.put("retryCount", task.retryCount());
        content.put("content", task.content());
        dlm.messageContent = content;

        dlm.deadLetteredAt = Instant.now();
        dlm.persist();

        logMqWarn(WARN_MARK, "已保存死信消息记录，id=%d, postId=%d", dlm.id, task.postId());
    }

    private void saveAuditDeadLetterMessage(AiAuditTask task) {
        DeadLetterMessage deadLetterMessage = new DeadLetterMessage();
        deadLetterMessage.sourceQueue = "ai-audit-tasks";
        deadLetterMessage.exchangeName = "ai-audit-tasks-dlx";
        deadLetterMessage.routingKey = "audit-dead";
        deadLetterMessage.retryCount = task.retryCount();
        deadLetterMessage.errorReason = "AI 评论审核失败（超过最大重试次数或处理超时）";

        Map<String, Object> messageContent = new HashMap<>();
        messageContent.put("commentId", task.commentId());
        messageContent.put("retryCount", task.retryCount());
        messageContent.put("content", task.content());
        deadLetterMessage.messageContent = messageContent;

        deadLetterMessage.deadLetteredAt = Instant.now();
        deadLetterMessage.persist();

        logMqWarn(WARN_MARK, "已保存审核死信消息记录，id=%d, commentId=%d",
                deadLetterMessage.id, task.commentId());
    }
}
