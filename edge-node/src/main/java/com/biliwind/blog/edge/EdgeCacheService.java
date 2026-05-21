package com.biliwind.blog.edge;

import com.biliwind.blog.model.Media;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.client.RedisClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

@ApplicationScoped
public class EdgeCacheService {
    private static final Logger log = LoggerFactory.getLogger(EdgeCacheService.class);
    private static final String MEDIA_KEY_PREFIX = "media:meta:";
    private static final String POST_KEY_PREFIX = "post:meta:";

    @Inject
    RedisClient redisClient;

    @Inject
    ObjectMapper objectMapper;

    public Media getMedia(String storageKey) {
        String cacheKey = MEDIA_KEY_PREFIX + storageKey;
        try {
            io.vertx.redis.client.Response value = redisClient.get(cacheKey);
            if (value != null) {
                return objectMapper.readValue(value.toString(), Media.class);
            }
        } catch (Exception e) {
            log.error("Failed to get media from cache: {}", storageKey, e);
        }
        return null;
    }

    public void setMedia(Media media) {
        if (media == null || media.storageKey == null) return;
        String cacheKey = MEDIA_KEY_PREFIX + media.storageKey;
        try {
            String json = objectMapper.writeValueAsString(media);
            redisClient.setex(cacheKey, "3600", json);
        } catch (Exception e) {
            log.error("Failed to set media in cache: {}", media.storageKey, e);
        }
    }

    public com.biliwind.blog.model.Post getPost(String slug) {
        String cacheKey = POST_KEY_PREFIX + slug;
        try {
            io.vertx.redis.client.Response value = redisClient.get(cacheKey);
            if (value != null) {
                return objectMapper.readValue(value.toString(), com.biliwind.blog.model.Post.class);
            }
        } catch (Exception e) {
            log.error("Failed to get post from cache: {}", slug, e);
        }
        return null;
    }

    public void setPost(com.biliwind.blog.model.Post post) {
        if (post == null || post.slug == null) return;
        String cacheKey = POST_KEY_PREFIX + post.slug;
        try {
            String json = objectMapper.writeValueAsString(post);
            redisClient.setex(cacheKey, "1800", json);
        } catch (Exception e) {
            log.error("Failed to set post in cache: {}", post.slug, e);
        }
    }

    public void invalidateMedia(String storageKey) {
        redisClient.del(Arrays.asList(MEDIA_KEY_PREFIX + storageKey));
    }

    public void invalidatePost(String slug) {
        redisClient.del(Arrays.asList(POST_KEY_PREFIX + slug));
    }

    public void invalidatePublicListCaches() {
        deletePattern("windblog:cache:index:page:*");
        deletePattern("windblog:cache:sidebar:recentPosts:*");
        deletePattern("windblog:cache:sidebar:categories:*");
        deletePattern("windblog:cache:sidebar:tags:*");
        redisClient.del(Arrays.asList("windblog:cache:sidebar:stats"));
    }

    private void deletePattern(String pattern) {
        try {
            io.vertx.redis.client.Response keysResponse = redisClient.keys(pattern);
            if (keysResponse == null) {
                return;
            }
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            for (int index = 0; index < keysResponse.size(); index++) {
                keys.add(keysResponse.get(index).toString());
            }
            if (!keys.isEmpty()) {
                redisClient.del(keys);
            }
        } catch (Exception e) {
            log.warn("Failed to invalidate cache pattern: {}", pattern, e);
        }
    }
}
