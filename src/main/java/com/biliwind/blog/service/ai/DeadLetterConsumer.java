package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.DeadLetterMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 死信消息消费者，处理进入死信队列的消息
 * 负责记录死信消息信息，用于后续分析和告警
 */
@ApplicationScoped
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /**
     * 消费死信队列中的消息
     * 记录死信消息的详细信息，包括原始消息内容、错误原因等
     * 
     * @param message 死信消息
     * @return 处理结果的 CompletionStage
     */
    @Incoming("ai-summary-dead-letter-in")
    @Transactional
    public CompletionStage<Void> consume(Message<AiSummaryTask> message) {
        AiSummaryTask task = message.getPayload();
        
        log.warn("收到死信消息：postId={}, priority={}, retryCount={}", 
                task.postId(), task.priority(), task.retryCount());
        
        try {
            // 保存死信消息记录到数据库
            saveDeadLetterMessage(task);
            
            // 记录死信消息的详细信息
            log.error("死信消息详情 - postId={}, priority={}, 重试次数={}, 任务内容={}", 
                     task.postId(), task.priority(), task.retryCount(), task.content());
            
            // 这里可以添加告警逻辑，例如：
            // - 发送通知到管理员邮箱
            // - 发送到 Slack/钉钉等告警渠道
            
            // 自动确认消息（死信消息不需要重试）
            message.ack().toCompletableFuture().join();
            
            log.info("死信消息已处理，postId={}", task.postId());
            
        } catch (Exception e) {
            log.error("处理死信消息时发生错误，postId={}", task.postId(), e);
            // 即使处理出错也要确认消息，避免无限循环
            message.ack().toCompletableFuture().join();
        }
        
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 保存死信消息到数据库
     */
    private void saveDeadLetterMessage(AiSummaryTask task) {
        try {
            DeadLetterMessage dlm = new DeadLetterMessage();
            dlm.sourceQueue = "ai-summary-tasks";
            dlm.exchangeName = "ai-tasks-dlx";
            dlm.routingKey = "summary-dead";
            dlm.postId = task.postId();
            dlm.priority = task.priority();
            dlm.retryCount = task.retryCount();
            dlm.errorReason = "AI 摘要处理失败（超过最大重试次数或处理超时）";
            
            // 保存消息内容
            Map<String, Object> content = new HashMap<>();
            content.put("postId", task.postId());
            content.put("priority", task.priority());
            content.put("retryCount", task.retryCount());
            content.put("content", task.content());
            dlm.messageContent = content;
            
            dlm.deadLetteredAt = Instant.now();
            dlm.persist();
            
            log.info("已保存死信消息记录，id={}, postId={}", dlm.id, task.postId());
        } catch (Exception e) {
            log.error("保存死信消息记录失败，postId={}", task.postId(), e);
            // 不影响消息确认
        }
    }
}
