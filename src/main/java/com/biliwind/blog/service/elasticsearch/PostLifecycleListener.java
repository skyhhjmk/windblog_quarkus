package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 文章生命周期监听器
 * 监听文章创建、更新和删除事件，自动同步到 Elasticsearch
 * 支持服务降级，当 Elasticsearch 不可用时缓存事件
 */
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
    ElasticsearchConnectionManager connectionManager;

    void onStart(@Observes StartupEvent event) {
        executorService = Executors.newFixedThreadPool(2);
        log.info("PostLifecycleListener 启动");

        startPendingEventsProcessor();
    }

    /**
     * 启动待处理事件处理器
     * 定期尝试处理因 Elasticsearch 不可用而缓存的事件
     */
    private void startPendingEventsProcessor() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "es-pending-events-processor");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(this::processPendingEvents, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 处理待处理的事件
     */
    private void processPendingEvents() {
        if (!connectionManager.isAvailable() || pendingEvents.isEmpty()) {
            return;
        }

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
                log.errorf("处理待处理事件失败: %d", event.postId(), e);
                if (pendingEvents.size() < 900) {
                    pendingEvents.offer(event);
                }
            }
        }
    }

    /**
     * 监听 Post 实体更新事件
     * 在事务提交后异步同步到 Elasticsearch
     */
    public void onPostUpdate(@ObservesAsync Post post) {
        if (post == null) {
            return;
        }

        final Long postId = post.id;

        if (!connectionManager.isAvailable()) {
            log.debugf("Elasticsearch 不可用，缓存文章更新事件: %d", postId);
            cacheEvent(new PostEvent(postId, EventType.UPDATE));
            return;
        }

        executorService.submit(() -> {
            try {
                processPostUpdate(postId);
            } catch (Exception e) {
                log.errorf("同步文章到 Elasticsearch 失败: %d", postId, e);
                cacheEvent(new PostEvent(postId, EventType.UPDATE));
            }
        });
    }

    /**
     * 处理文章更新
     */
    private void processPostUpdate(Long postId) throws Exception {
        log.infof("检测到文章更新事件: %d", postId);

        Post refreshedPost = entityManager.find(Post.class, postId);
        if (refreshedPost == null) {
            log.warnf("文章不存在: %d，跳过索引", postId);
            return;
        }

        if (refreshedPost.status == PostStatus.PUBLISHED) {
            List<String> tags = postSearchService.getPostTags(postId);
            postSearchService.indexPost(refreshedPost, tags);
            log.infof("文章已同步到 Elasticsearch: %d (包含 %d 个标签)", postId, tags.size());
        } else {
            postSearchService.deletePostIndex(postId);
            log.infof("文章索引已删除: %d", postId);
        }
    }

    /**
     * 监听 Post 实体删除事件
     */
    public void onPostDelete(@Observes Post post) {
        if (post == null) {
            return;
        }

        final Long postId = post.id;

        if (!connectionManager.isAvailable()) {
            log.debugf("Elasticsearch 不可用，缓存文章删除事件: %d", postId);
            cacheEvent(new PostEvent(postId, EventType.DELETE));
            return;
        }

        executorService.submit(() -> {
            try {
                processPostDelete(postId);
            } catch (Exception e) {
                log.errorf("删除文章索引失败: %d", postId, e);
                cacheEvent(new PostEvent(postId, EventType.DELETE));
            }
        });
    }

    /**
     * 处理文章删除
     */
    private void processPostDelete(Long postId) throws Exception {
        log.infof("检测到文章删除事件: %d", postId);
        postSearchService.deletePostIndex(postId);
        log.infof("文章索引已删除: %d", postId);
    }

    /**
     * 缓存事件
     */
    private void cacheEvent(PostEvent event) {
        if (!pendingEvents.offer(event)) {
            log.warnf("事件队列已满，丢弃事件: %d", event.postId());
        }
    }

    /**
     * 应用关闭时关闭线程池
     */
    void onStop(@Observes io.quarkus.runtime.ShutdownEvent event) {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        log.info("PostLifecycleListener 停止");
    }

    /**
     * 事件类型
     */
    private enum EventType {
        UPDATE,
        DELETE
    }

    /**
     * 文章事件
     */
    private record PostEvent(Long postId, EventType type) {
    }
}
