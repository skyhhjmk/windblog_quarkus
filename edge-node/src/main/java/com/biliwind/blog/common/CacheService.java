package com.biliwind.blog.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.KeyScanCursor;
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
        if (!redisAvailable) {
            return;
        }
        try {
            KeyScanArgs args = new KeyScanArgs().match(CACHE_PREFIX + pattern);
            KeyScanCursor<String> cursor = keyCommands.scan(args);
            while (cursor.hasNext()) {
                java.util.Set<String> keys = cursor.next();
                if (keys.isEmpty()) {
                    continue;
                }
                // 使用批量删除减少网络往返
                keyCommands.del(keys.toArray(new String[0]));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to delete cache pattern: %s", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return redisAvailable;
    }

    public static class Keys {
        public static final String SIDEBAR_RECENT_POSTS = "sidebar:recentPosts:";
        public static final String SIDEBAR_CATEGORIES = "sidebar:categories:";
        public static final String SIDEBAR_TAGS = "sidebar:tags:";
        public static final String SIDEBAR_STATS = "sidebar:stats";
        public static final String INDEX_PAGE_PREFIX = "index:page:";
        public static final String POST_META_PREFIX = "post:meta:";
        public static final String MEDIA_META_PREFIX = "media:meta:";

        public static final String ALL_CATEGORIES = "categories:all";
        public static final String ALL_TAGS = "tags:all";
        public static final String TAG_POST_COUNT_PREFIX = "tags:postCount:";
        public static final String CATEGORY_POST_COUNT_PREFIX = "categories:postCount:";
        public static final String ALL_POSTS = "posts:all";

        public static String indexPage(int page, String lang) {
            return INDEX_PAGE_PREFIX + page + ":lang:" + lang;
        }

        public static String recentPosts(String lang, int limit) {
            return SIDEBAR_RECENT_POSTS + lang + ":" + limit;
        }

        public static String categories(String lang) {
            return SIDEBAR_CATEGORIES + lang;
        }

        public static String tags(String lang) {
            return SIDEBAR_TAGS + lang;
        }

        public static String postMeta(String slug) {
            return POST_META_PREFIX + slug;
        }

        public static String mediaMeta(String storageKey) {
            return MEDIA_META_PREFIX + storageKey;
        }
    }
}
