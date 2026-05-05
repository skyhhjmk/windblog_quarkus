package com.biliwind.blog.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);
    private static final String CACHE_PREFIX = "windblog:cache:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final ObjectMapper objectMapper;
    private ValueCommands<String, String> valueCommands;
    private KeyCommands<String> keyCommands;
    private boolean redisAvailable = false;

    @Inject
    public CacheService(ObjectMapper objectMapper, Instance<RedisDataSource> redisDataSourceInstance) {
        this.objectMapper = objectMapper;
        try {
            RedisDataSource redisDataSource = redisDataSourceInstance.get();
            this.valueCommands = redisDataSource.value(String.class);
            this.keyCommands = redisDataSource.key(String.class);
            this.redisAvailable = true;
            LOG.infof("Redis cache service initialized successfully");
        } catch (Exception e) {
            LOG.warnf("Redis is not available, cache will be disabled: %s", e.getMessage());
            this.redisAvailable = false;
        }
    }

    public <T> void set(String key, T value) {
        set(key, value, DEFAULT_TTL);
    }

    public <T> void set(String key, T value, Duration ttl) {
        if (!redisAvailable) return;
        try {
            String json = objectMapper.writeValueAsString(value);
            valueCommands.set(CACHE_PREFIX + key, json, new SetArgs().ex(ttl.toSeconds()));
        } catch (Exception e) {
            LOG.warnf("Failed to set cache value: %s", e.getMessage());
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (!redisAvailable) return Optional.empty();
        try {
            String json = valueCommands.get(CACHE_PREFIX + key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            LOG.warnf("Failed to get cache value: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public <T> Optional<T> get(String key, TypeReference<T> typeRef) {
        if (!redisAvailable) return Optional.empty();
        try {
            String json = valueCommands.get(CACHE_PREFIX + key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, typeRef));
        } catch (Exception e) {
            LOG.warnf("Failed to get cache value: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String key) {
        if (!redisAvailable) return;
        try {
            valueCommands.getdel(CACHE_PREFIX + key);
        } catch (Exception e) {
            LOG.warnf("Failed to delete cache: %s", e.getMessage());
        }
    }

    public void deletePattern(String pattern) {
        if (!redisAvailable) return;
        try {
            var keys = keyCommands.keys(CACHE_PREFIX + pattern);
            for (String key : keys) {
                valueCommands.getdel(key);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to delete cache pattern: %s", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return redisAvailable;
    }

    public static class Keys {
        public static final String ALL_CATEGORIES = "categories:all";
        public static final String ALL_TAGS = "tags:all";
        public static final String TAG_POST_COUNT_PREFIX = "tags:postCount:";
        public static final String CATEGORY_POST_COUNT_PREFIX = "categories:postCount:";
        public static final String ALL_POSTS = "posts:all";
    }
}
