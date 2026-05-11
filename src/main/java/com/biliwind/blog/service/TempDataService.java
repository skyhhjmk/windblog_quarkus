package com.biliwind.blog.service;

import com.biliwind.blog.model.TempData;
import com.biliwind.blog.service.elasticsearch.EsSyncTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class TempDataService {

    private static final Logger log = Logger.getLogger(TempDataService.class);
    private static final String ES_SYNC_TASK_TYPE = "es_sync_task";

    @Inject
    ObjectMapper mapper;

    @Inject
    @Channel("es-sync-tasks")
    Emitter<EsSyncTask> esSyncEmitter;

    @Transactional
    public void save(String type, String target, JsonNode payload) {
        TempData data = new TempData();
        data.type = type;
        data.target = target;
        data.payload = payload;
        data.status = "pending";
        data.persist();
        log.infof("临时数据已保存: type=%s, target=%s", type, target);
    }

    @Scheduled(every = "60s")
    @Transactional
    public void retryPendingTasks() {
        retryTasksByType(ES_SYNC_TASK_TYPE);
    }

    private void retryTasksByType(String type) {
        List<TempData> pendingTasks = TempData.findPendingByType(type);
        if (pendingTasks.isEmpty()) {
            return;
        }

        log.infof("开始重试 %d 个 %s 类型的待处理任务", pendingTasks.size(), type);

        for (TempData task : pendingTasks) {
            if (task.retryCount >= task.maxRetries) {
                task.status = "failed";
                task.errorMsg = "超过最大重试次数";
                task.persist();
                log.warnf("任务超过最大重试次数，标记为失败: id=%d, type=%s, target=%s", task.id, task.type, task.target);
                continue;
            }

            try {
                task.status = "processing";
                task.retryCount++;
                task.persist();

                if (ES_SYNC_TASK_TYPE.equals(type)) {
                    EsSyncTask syncTask = parseEsSyncTask(task.payload);
                    esSyncEmitter.send(syncTask);
                    log.infof("任务重试成功，已发送到 RabbitMQ: id=%d, target=%s", task.id, task.target);
                }

                task.status = "completed";
                task.persist();
            } catch (Exception e) {
                task.status = "pending";
                task.errorMsg = e.getMessage();
                task.persist();
                log.errorf("任务重试失败: id=%d, type=%s, target=%s, 错误: %s", task.id, task.type, task.target, e.getMessage());
            }
        }
    }

    private EsSyncTask parseEsSyncTask(JsonNode payload) {
        Long postId = payload.get("postId").asLong();
        String actionType = payload.get("actionType").asText();
        return new EsSyncTask(postId, actionType);
    }

    public JsonNode createSyncTaskPayload(Long postId, String actionType) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("postId", postId);
        payload.put("actionType", actionType);
        return payload;
    }
}
