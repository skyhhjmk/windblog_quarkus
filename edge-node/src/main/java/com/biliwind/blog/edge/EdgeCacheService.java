package com.biliwind.blog.edge;

import com.biliwind.blog.model.Media;
import io.quarkus.redis.client.RedisClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            var value = redisClient.get(cacheKey);
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
            var value = redisClient.get(cacheKey);
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
}
