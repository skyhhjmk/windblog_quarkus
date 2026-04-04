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

/**
 * Article lifecycle listener
 * Monitors article creation, update, and deletion, automatically synchronizing to Elasticsearch
 */
@ApplicationScoped
public class PostLifecycleListener {

    private static final Logger log = Logger.getLogger(PostLifecycleListener.class);

    @Inject
    ElasticsearchPostSearchService postSearchService;

    @Inject
    EntityManager entityManager;

    private ExecutorService executorService;

    void onStart(@Observes StartupEvent event) {
        executorService = Executors.newFixedThreadPool(2);
        log.info("PostLifecycleListener started");
    }

    /**
     * Monitor Post entity update events
     * Asynchronously sync to Elasticsearch after transaction commit
     */
    public void onPostUpdate(@ObservesAsync Post post) {
        if (post == null) {
            return;
        }

        final Long postId = post.id;
        
        executorService.submit(() -> {
            try {
                log.infof("Detected article update event: %d", postId);

                Post refreshedPost = entityManager.find(Post.class, postId);
                if (refreshedPost == null) {
                    log.warnf("Article not found: %d, skipping index", postId);
                    return;
                }

                if (refreshedPost.status == PostStatus.PUBLISHED) {
                    List<String> tags = postSearchService.getPostTags(postId);
                    postSearchService.indexPost(refreshedPost, tags);
                    log.infof("Article synchronized to Elasticsearch: %d (with %d tags)", postId, tags.size());
                } else {
                    postSearchService.deletePostIndex(postId);
                    log.infof("Article index deleted: %d", postId);
                }
            } catch (Exception e) {
                log.errorf("Failed to sync article to Elasticsearch: %d", postId, e);
            }
        });
    }

    /**
     * Monitor Post entity deletion events
     */
    public void onPostDelete(@Observes Post post) {
        if (post == null) {
            return;
        }

        final Long postId = post.id;
        
        executorService.submit(() -> {
            try {
                log.infof("Detected article deletion event: %d", postId);
                postSearchService.deletePostIndex(postId);
                log.infof("Article index deleted: %d", postId);
            } catch (Exception e) {
                log.errorf("Failed to delete article index: %d", postId, e);
            }
        });
    }

    /**
     * Shutdown thread pool when application closes
     */
    void onStop(@Observes io.quarkus.runtime.ShutdownEvent event) {
        if (executorService != null) {
            executorService.shutdown();
            log.info("PostLifecycleListener stopped");
        }
    }
}
