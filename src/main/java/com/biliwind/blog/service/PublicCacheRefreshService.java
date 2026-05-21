package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

@ApplicationScoped
public class PublicCacheRefreshService {

    private static final Logger LOG = Logger.getLogger(PublicCacheRefreshService.class);

    @Inject
    CacheService cacheService;

    public void onPostChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) PostSyncedEvent event) {
        refreshPostCaches(event.getPostId());
    }

    public void onDataChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) DataSyncEvent event) {
        String entityType = event.getEntityType();
        if ("CATEGORY".equals(entityType)) {
            refreshCategoryCaches();
        } else if ("TAG".equals(entityType)) {
            refreshTagCaches();
        } else if ("MEDIA".equals(entityType)) {
            refreshMediaCache(event.getEntityId(), event.getAction());
        } else if ("LINK".equals(entityType)) {
            refreshListCaches();
        }
    }

    public void refreshPostCaches(Long postId) {
        try {
            Post post = Post.findById(postId);
            if (post != null && post.slug != null && post.deletedAt == null) {
                cacheService.set(CacheService.Keys.postMeta(post.slug), post, Duration.ofMinutes(30));
            }
            refreshListCaches();
        } catch (Exception e) {
            LOG.warnf("刷新文章缓存失败: postId=%d, error=%s", postId, e.getMessage());
        }
    }

    public void refreshCategoryCaches() {
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_CATEGORIES + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }

    public void refreshTagCaches() {
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_TAGS + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }

    public void refreshMediaCache(Long mediaId, String action) {
        try {
            Media media = Media.findById(mediaId);
            if (media == null) {
                return;
            }
            if (media.storageKey == null) {
                return;
            }
            if ("DELETE".equals(action)) {
                cacheService.delete(CacheService.Keys.mediaMeta(media.storageKey));
            } else {
                cacheService.set(CacheService.Keys.mediaMeta(media.storageKey), media, Duration.ofHours(1));
            }
        } catch (Exception e) {
            LOG.warnf("刷新媒体缓存失败: mediaId=%d, error=%s", mediaId, e.getMessage());
        }
    }

    private void refreshListCaches() {
        cacheService.deletePattern(CacheService.Keys.INDEX_PAGE_PREFIX + "*");
        cacheService.deletePattern(CacheService.Keys.SIDEBAR_RECENT_POSTS + "*");
        cacheService.delete(CacheService.Keys.SIDEBAR_STATS);
    }
}
