package com.biliwind.blog.service.elasticsearch;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * Elasticsearch 同步任务消费者
 * <p>
 * 监听 RabbitMQ 中的 es-sync-tasks-in 通道，执行延迟同步逻辑。
 */
@ApplicationScoped
public class EsSyncConsumer {

    private static final Logger log = Logger.getLogger(EsSyncConsumer.class);

    @Inject
    PostLifecycleListener lifecycleListener;

    @Incoming("es-sync-tasks-in")
    @Blocking
    @Transactional
    public CompletionStage<Void> consume(Message<EsSyncTask> message) {
        EsSyncTask task = message.getPayload();
        log.infof("收到 ES 同步任务: postId=%d, action=%s", task.postId(), task.actionType());

        try {
            if ("UPDATE".equals(task.actionType())) {
                lifecycleListener.processPostUpdate(task.postId());
            } else if ("DELETE".equals(task.actionType())) {
                lifecycleListener.processPostDelete(task.postId());
            }

            log.infof("ES 同步任务处理成功: %d", task.postId());
            return message.ack();
        } catch (Exception e) {
            log.errorf("ES 同步任务处理失败: %d, 错误: %s. 消息将保留在队列中重试。", task.postId(), e.getMessage());
            // 返回 nack 以便 RabbitMQ 进行重试 (根据配置可能会进入死信队列或重新入队)
            return message.nack(e);
        }
    }
}
