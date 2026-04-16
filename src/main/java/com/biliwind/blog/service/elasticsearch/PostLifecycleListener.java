package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.*;

@ApplicationScoped
public class PostLifecycleListener {

    private static final Logger log = Logger.getLogger(PostLifecycleListener.class);

    @Inject
    ElasticsearchPostSearchService postSearchService;

    private final LinkedBlockingQueue<PostEvent> pendingEvents = new LinkedBlockingQueue<>(1000);

    @Inject
    EntityManager entityManager;

    private ExecutorService executorService;
    @Inject
    RequestContextController requestContextController;
    @Inject
    ElasticsearchConnectionManager connectionManager;
    private ScheduledExecutorService scheduledExecutorService;

    void onStart(@Observes StartupEvent event) {
        log.info("========================================");
        log.info("PostLifecycleListener 初始化中...");
        executorService = Executors.newFixedThreadPool(2);
        log.info("PostLifecycleListener 启动完成, executorService: " + (executorService != null ? "OK" : "NULL"));

        startPendingEventsProcessor();
        log.info("========================================");
    }

    private void startPendingEventsProcessor() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "es-pending-events-processor");
            t.setDaemon(true);
            return t;
        });
        scheduledExecutorService.scheduleWithFixedDelay(this::processPendingEvents, 30, 30, TimeUnit.SECONDS);
    }

    private void processPendingEvents() {
        if (!connectionManager.isAvailable() || pendingEvents.isEmpty()) {
            return;
        }

        requestContextController.activate();
        try {
            List<PostEvent> batch = new java.util.ArrayList<>();
            pendingEvents.drainTo(batch, 50);

            for (PostEvent event : batch) {
                try {
                    if (event.type() == EventType.UPDATE) {
                        processPostUpdate(event.postId());
                    } else if (event.type() == EventType.DELETE) {
                        processPostDelete(event.postId());
                    }
                } catch (Exception e) {
                    log.errorf("处理待处理事件失败: %d, 错误: %s", event.postId(), e.getMessage());
                    log.debugf("待处理事件处理异常详情: ", e);
                    if (pendingEvents.size() < 900) {
                        pendingEvents.offer(event);
                    }
                }
            }
        } finally {
            requestContextController.deactivate();
        }
    }

    public void onPostUpdate(@ObservesAsync Post post) {
        log.info(">>>>>>>>>> onPostUpdate 事件接收到了! postId=" + (post != null ? post.id : "null"));
        if (post == null) {
            log.warn(">>>>>>>>>> post 是 null，直接返回");
            return;
        }

        final Long postId = post.id;
        log.info(">>>>>>>>>> 处理文章更新事件: " + postId);

        log.debugf("收到文章更新事件，准备同步: %d", postId);

        if (!connectionManager.isAvailable()) {
            log.debugf("Elasticsearch 不可用，缓存文章更新事件: %d", postId);
            cacheEvent(new PostEvent(postId, EventType.UPDATE));
            return;
        }

        executorService.submit(() -> {
            requestContextController.activate();
            try {
                processPostUpdate(postId);
            } catch (Exception e) {
                log.errorf("同步文章到 Elasticsearch 失败: %d, 错误: %s", postId, e.getMessage());
                log.debugf("同步异常详细: ", e);
                cacheEvent(new PostEvent(postId, EventType.UPDATE));
            } finally {
                requestContextController.deactivate();
            }
        });
    }

    private void processPostUpdate(Long postId) throws Exception {
        log.info(">>>>>>>>>> [processPostUpdate] 开始处理, postId=" + postId);

        if (!connectionManager.isAvailable()) {
            log.warnf("Elasticsearch 不可用，跳过文章索引: %d", postId);
            cacheEvent(new PostEvent(postId, EventType.UPDATE));
            return;
        }

        if (!postSearchService.isAvailable()) {
            log.warnf("Elasticsearch 文章索引未初始化，跳过文章索引: %d", postId);
            cacheEvent(new PostEvent(postId, EventType.UPDATE));
            return;
        }

        log.info(">>>>>>>>>> [processPostUpdate] 准备从数据库加载文章: " + postId);
        Post refreshedPost = entityManager.find(Post.class, postId);
        if (refreshedPost == null) {
            log.warnf("文章不存在: %d，跳过索引", postId);
            return;
        }

        org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
        session.evict(refreshedPost);
        refreshedPost = entityManager.find(Post.class, postId);
        log.info(">>>>>>>>>> [processPostUpdate] 强制清除缓存后重新加载");

        log.info(">>>>>>>>>> [processPostUpdate] 文章已加载, id=" + postId + ", status=" + refreshedPost.status + ", deletedAt=" + refreshedPost.deletedAt + ", title=" + refreshedPost.title + ", currentRevision=" + (refreshedPost.currentRevision != null ? refreshedPost.currentRevision.revisionNumber : "NULL"));

        if (refreshedPost.currentRevision != null) {
            log.info(">>>>>>>>>> [processPostUpdate] currentRevision.title=" + refreshedPost.currentRevision.title);
        }

        if (refreshedPost.deletedAt != null) {
            log.debugf("文章已软删除，删除索引: %d", postId);
            postSearchService.deletePostIndex(postId);
            log.infof("文章已软删除，索引已删除: %d", postId);
            return;
        }

        if (refreshedPost.status == PostStatus.PUBLISHED) {
            log.info(">>>>>>>>>> [processPostUpdate] 文章状态为 PUBLISHED，准备索引: " + postId);
            List<String> tags = postSearchService.getPostTags(postId);
            log.info(">>>>>>>>>> [processPostUpdate] 标签获取完成: " + tags);
            log.info(">>>>>>>>>> [processPostUpdate] 调用 indexPost, post.title=" + refreshedPost.title);
            postSearchService.indexPost(refreshedPost, tags);
            log.infof("文章已同步到 Elasticsearch: %d (包含 %d 个标签)", postId, tags.size());
        } else {
            log.info(">>>>>>>>>> [processPostUpdate] 文章状态不是 PUBLISHED: " + refreshedPost.status + "，删除索引");
            postSearchService.deletePostIndex(postId);
            log.infof("文章索引已删除: %d", postId);
        }
    }

    public void onPostDelete(@Observes Post post) {
        if (post == null) {
            return;
        }

        final Long postId = post.id;

        log.debugf("收到文章删除事件: %d", postId);

        if (!connectionManager.isAvailable()) {
            log.debugf("Elasticsearch 不可用，缓存文章删除事件: %d", postId);
            cacheEvent(new PostEvent(postId, EventType.DELETE));
            return;
        }

        executorService.submit(() -> {
            requestContextController.activate();
            try {
                processPostDelete(postId);
            } catch (Exception e) {
                log.errorf("删除文章索引失败: %d, 错误: %s", postId, e.getMessage());
                log.debugf("删除文章索引异常详情: ", e);
                cacheEvent(new PostEvent(postId, EventType.DELETE));
            } finally {
                requestContextController.deactivate();
            }
        });
    }

    private void processPostDelete(Long postId) throws Exception {
        log.infof("检测到文章删除事件: %d", postId);
        postSearchService.deletePostIndex(postId);
        log.infof("文章索引已删除: %d", postId);
    }

    private void cacheEvent(PostEvent event) {
        if (!pendingEvents.offer(event)) {
            log.warnf("事件队列已满，丢弃事件: %d", event.postId());
        }
    }

    void onStop(@Observes io.quarkus.runtime.ShutdownEvent event) {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("PostLifecycleListener 停止");
    }

    private enum EventType {
        UPDATE,
        DELETE
    }

    private record PostEvent(Long postId, EventType type) {
    }
}
