package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * AI 摘要任务消费者，处理来自 RabbitMQ 的 AI 摘要生成任务
 * 使用手动 ACK 机制确保消息可靠性
 */
@ApplicationScoped
public class AiTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskConsumer.class);

    @Inject
    AiManager aiManager;

    /**
     * 消费 AI 摘要任务
     * 使用手动 ACK 机制：处理成功发送 ACK，处理失败发送 NACK
     * 
     * @param message 包含任务消息和元数据
     * @return 处理结果的 CompletionStage
     */
    @Incoming("ai-summary-tasks-in")
    @Transactional
    public CompletionStage<Void> consume(Message<AiSummaryTask> message) {
        AiSummaryTask task = message.getPayload();
        
        log.info("开始处理 AI 摘要任务，postId={}, priority={}, retryCount={}", 
                task.postId(), task.priority(), task.retryCount());
        
        try {
            return aiManager.summarize(task.content())
                    .thenAccept(summaries -> {
                        try {
                            // 处理成功：更新数据库并发送 ACK
                            updatePostAiSummary(task.postId(), summaries);
                            message.ack().toCompletableFuture().join();
                            log.info("AI 摘要任务处理成功，postId={}", task.postId());
                        } catch (Exception e) {
                            log.error("更新文章摘要失败，postId={}", task.postId(), e);
                            message.nack(e).toCompletableFuture().join();
                        }
                    })
                    .exceptionally(ex -> {
                        // 处理失败：记录错误并发送 NACK，消息会进入死信队列
                        log.error("AI 摘要任务处理失败，postId={}, 错误：{}", 
                                task.postId(), ex.getMessage(), ex);
                        message.nack(ex).toCompletableFuture().join();
                        return null;
                    });
        } catch (Exception e) {
            log.error("消费消息时发生异常，postId={}", task.postId(), e);
            message.nack(e).toCompletableFuture().join();
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 更新文章的 AI 摘要
     */
    private void updatePostAiSummary(Long postId, Map<String, String> summaries) {
        Post post = Post.findById(postId);
        if (post != null) {
            post.aiSummary = summaries;
            post.persist();
            log.debug("已更新文章 AI 摘要，postId={}, 摘要语言数={}", postId, summaries.size());
        } else {
            log.warn("未找到文章，postId={}", postId);
        }
    }
}
