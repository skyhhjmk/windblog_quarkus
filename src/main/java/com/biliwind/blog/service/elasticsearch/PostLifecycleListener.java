package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    private ExecutorService executorService;

    void onStart(@Observes StartupEvent event) {
        // Create thread pool for asynchronous processing
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
        
        executorService.submit(() -> {
            try {
                log.infof("Detected article update event: %d", post.id);
                
                // If article is published, index to Elasticsearch
                if (post.status == PostStatus.PUBLISHED) {
                    postSearchService.indexPost(post);
                    log.infof("Article synchronized to Elasticsearch: %d", post.id);
                } else {
                    // If article is not published, delete index
                    postSearchService.deletePostIndex(post.id);
                    log.infof("Article index deleted: %d", post.id);
                }
            } catch (Exception e) {
                log.errorf("Failed to sync article to Elasticsearch: %d", post.id, e);
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
        
        executorService.submit(() -> {
            try {
                log.infof("Detected article deletion event: %d", post.id);
                postSearchService.deletePostIndex(post.id);
                log.infof("Article index deleted: %d", post.id);
            } catch (Exception e) {
                log.errorf("Failed to delete article index: %d", post.id, e);
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
