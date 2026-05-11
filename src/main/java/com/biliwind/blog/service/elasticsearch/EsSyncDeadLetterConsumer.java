package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.service.TempDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EsSyncDeadLetterConsumer {

    private static final Logger log = Logger.getLogger(EsSyncDeadLetterConsumer.class);

    @Inject
    TempDataService tempDataService;

    @Incoming("es-sync-tasks-dlq-in")
    public void consume(EsSyncTask task) {
        log.errorf("收到 ES 同步死信任务: postId=%d, action=%s", task.postId(), task.actionType());

        JsonNode payload = tempDataService.createSyncTaskPayload(task.postId(), task.actionType());
        if (payload instanceof ObjectNode) {
            ((ObjectNode) payload).put("source", "dead_letter_queue");
        }
        tempDataService.save("es_sync_task_dlq", task.postId().toString(), payload);
    }
}
