package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.service.TempDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.Map;

@ApplicationScoped
public class EsSyncDeadLetterConsumer {

    private static final Logger log = Logger.getLogger(EsSyncDeadLetterConsumer.class);

    @Inject
    TempDataService tempDataService;
    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;
    @Inject
    EsSyncMessageDecoder esSyncMessageDecoder;

    @Incoming("es-sync-tasks-dlq-in")
    @Transactional
    public void consume(org.eclipse.microprofile.reactive.messaging.Message<?> message) {
        if (nodeRoleService.isEdgeNode()) {
            log.debug("当前节点是边缘节点，忽略 ES 死信同步任务");
            return;
        }

        EsSyncTask task;
        try {
            task = esSyncMessageDecoder.decode(message.getPayload());
        } catch (Exception exception) {
            log.error("ES 死信同步消息无法解码，丢弃该消息", exception);
            return;
        }
        log.errorf("收到 ES 同步死信任务: postId=%d, action=%s", task.postId(), task.actionType());

        JsonNode payload = tempDataService.createSyncTaskPayload(task.postId(), task.actionType());
        if (payload instanceof ObjectNode) {
            ((ObjectNode) payload).put("source", "dead_letter_queue");
        }
        tempDataService.save("es_sync_task_dlq", task.postId().toString(), payload);

        com.biliwind.blog.model.DeadLetterMessage dlm = new com.biliwind.blog.model.DeadLetterMessage();
        dlm.sourceQueue = "es-sync-tasks";
        dlm.exchangeName = "es-sync-tasks-dlx";
        dlm.routingKey = "es-sync-tasks";
        dlm.postId = task.postId();
        dlm.retryCount = 0;
        dlm.errorReason = "Elasticsearch 同步失败进入死信队列";
        dlm.messageContent = Map.of("postId", task.postId(), "actionType", task.actionType());
        dlm.isProcessed = false;
        dlm.persist();
    }
}
