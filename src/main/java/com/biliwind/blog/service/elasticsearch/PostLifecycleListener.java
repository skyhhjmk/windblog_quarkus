package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.service.TempDataService;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class PostLifecycleListener {

    private static final Logger log = Logger.getLogger(PostLifecycleListener.class);

    @Inject
    ElasticsearchPostSearchService postSearchService;

    @Inject
    EntityManager entityManager;

    @Inject
    ElasticsearchConnectionManager connectionManager;

    @Inject
    @Channel("es-sync-tasks")
    Instance<Emitter<EsSyncTask>> esSyncEmitter;

    @Inject
    TempDataService tempDataService;

    @Inject
    CacheService cacheService;
    @Inject
    NodeRoleService nodeRoleService;

    private void invalidatePostCaches() {
        // 清理首页缓存 (1-5页)
        cacheService.deletePattern(CacheService.Keys.INDEX_PAGE_PREFIX + "*");
        // 清理侧边栏最新文章 (按语言)
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_RECENT_POSTS + "*");
        // 清理侧边栏统计
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }

    void onStart(@Observes StartupEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            log.info("当前节点是边缘节点，跳过 Elasticsearch 文章生命周期监听初始化");
            return;
        }
        log.info("========================================");
        log.info("PostLifecycleListener 初始化完成");
        log.info("========================================");
    }

    public void onPostSynced(@Observes(during = TransactionPhase.AFTER_SUCCESS) PostSyncedEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            invalidatePostCaches();
            return;
        }

        log.debug("[onPostSynced] 收到文章同步事件: " + event.getPostId());

        if (!connectionManager.isAvailable()) {
            log.warnf("Elasticsearch 当前不可用，发送同步任务到 RabbitMQ 持久化: %d", event.getPostId());
            sendToQueue(event.getPostId(), "UPDATE");
            return;
        }

        try {
            processPostUpdate(event.getPostId());
        } catch (Exception e) {
            log.errorf("同步文章到 Elasticsearch 失败: %d, 错误: %s", event.getPostId(), e.getMessage());
            sendToQueue(event.getPostId(), "UPDATE");
        }
    }

    /**
     * 核心业务逻辑：将数据库文章状态同步到 ES
     */
    public void processPostUpdate(Long postId) throws Exception {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }

        log.debugf("开始处理文章同步, postId=%d", postId);

        if (!connectionManager.isAvailable()) {
            throw new RuntimeException("Elasticsearch 不可用");
        }

        if (!postSearchService.isAvailable()) {
            log.warnf("Elasticsearch 文章索引未初始化: %d", postId);
            throw new RuntimeException("Elasticsearch 索引未就绪");
        }

        Post refreshedPost = entityManager.find(Post.class, postId);
        if (refreshedPost == null) {
            log.warnf("文章不存在: %d，尝试删除索引", postId);
            try {
                postSearchService.deletePostIndex(postId);
            } catch (Exception e) {
                log.errorf("删除不存在文章的索引失败（可忽略）: %d, 错误: %s", postId, e.getMessage());
            }
            return;
        }

        if (refreshedPost.deletedAt != null || refreshedPost.visibility != 0 || refreshedPost.publishedRevision == null) {
            log.debugf("文章不可公开展示，删除索引: %d", postId);
            postSearchService.deletePostIndex(postId);
            return;
        }

        if (refreshedPost.status == PostStatus.PUBLISHED) {
            List<String> tags = postSearchService.getPostTags(postId);
            postSearchService.indexPost(refreshedPost, tags);
            log.infof("文章已同步到 Elasticsearch: %d", postId);
        } else {
            log.debugf("文章状态为 %s，确保索引已删除: %d", refreshedPost.status, postId);
            postSearchService.deletePostIndex(postId);
        }

        invalidatePostCaches();
    }

    public void processPostDelete(Long postId) throws Exception {
        if (nodeRoleService.isEdgeNode()) {
            invalidatePostCaches();
            return;
        }

        log.infof("检测到文章硬删除事件: %d", postId);
        postSearchService.deletePostIndex(postId);
        invalidatePostCaches();
    }

    private void sendToQueue(Long postId, String actionType) {
        try {
            esSyncEmitter.get().send(new EsSyncTask(postId, actionType));
            log.infof("同步任务已发送至 RabbitMQ: postId=%d, action=%s", postId, actionType);
        } catch (Exception e) {
            log.errorf("发送同步任务到 RabbitMQ 失败，保存到 temp_data 表: %d, 错误: %s", postId, e.getMessage());
            JsonNode payload = tempDataService.createSyncTaskPayload(postId, actionType);
            tempDataService.save("es_sync_task", postId.toString(), payload);
        }
    }

    void onStop(@Observes io.quarkus.runtime.ShutdownEvent event) {
        log.info("PostLifecycleListener 停止");
    }
}
