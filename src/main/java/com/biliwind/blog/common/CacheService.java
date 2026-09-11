package com.biliwind.blog.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.KeyScanCursor;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);
    private static final String CACHE_PREFIX = "windblog:cache:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final ObjectMapper objectMapper;
    private final Instance<RedisDataSource> redisDataSourceInstance;
    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "default")
    String sitePublicUrl;
    private volatile String cachePrefix = CACHE_PREFIX;
    private ValueCommands<String, String> valueCommands;
    private KeyCommands<String> keyCommands;
    private volatile boolean redisAvailable = false;
    private volatile long nextReconnectAtMillis = 0L;

    @Inject
    public CacheService(ObjectMapper objectMapper, Instance<RedisDataSource> redisDataSourceInstance) {
        this.objectMapper = objectMapper;
        this.redisDataSourceInstance = redisDataSourceInstance;
        reconnectIfNeeded();
    }

    @PostConstruct
    void initializeCacheNamespace() {
        String raw = sitePublicUrl == null ? "" : sitePublicUrl.trim();
        String namespace = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (namespace.isBlank()) {
            namespace = "default";
        }
        cachePrefix = CACHE_PREFIX + namespace + ":";
    }

    public <T> void set(String key, T value) {
        set(key, value, DEFAULT_TTL);
    }

    public <T> void set(String key, T value, Duration ttl) {
        if (!isRedisAvailable()) return;
        try {
            String json = objectMapper.writeValueAsString(value);
            valueCommands.set(cachePrefix + key, json, new SetArgs().ex(ttl.toSeconds()));
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to set cache value: %s", e.getMessage());
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (!isRedisAvailable()) return Optional.empty();
        try {
            String json = valueCommands.get(cachePrefix + key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to get cache value: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public <T> Optional<T> get(String key, TypeReference<T> typeRef) {
        if (!isRedisAvailable()) return Optional.empty();
        try {
            String json = valueCommands.get(cachePrefix + key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, typeRef));
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to get cache value: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String key) {
        if (!isRedisAvailable()) return;
        try {
            valueCommands.getdel(cachePrefix + key);
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to delete cache: %s", e.getMessage());
        }
    }

    public long increment(String key, Duration ttl) {
        if (!isRedisAvailable()) {
            return -1L;
        }
        try {
            String fullKey = cachePrefix + key;
            long value = valueCommands.incr(fullKey);
            if (value == 1L) {
                keyCommands.expire(fullKey, ttl);
            }
            return value;
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to increment cache counter: %s", e.getMessage());
            return -1L;
        }
    }

    /** Increments a bounded Redis bucket by a byte or cost amount. */
    public long incrementBy(String key, long amount, Duration ttl) {
        if (!isRedisAvailable()) {
            return -1L;
        }
        if (amount < 1) {
            return 0L;
        }
        try {
            String fullKey = cachePrefix + key;
            long value = valueCommands.incrby(fullKey, amount);
            if (value == amount) {
                keyCommands.expire(fullKey, ttl);
            }
            return value;
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to increment weighted cache counter: %s", e.getMessage());
            return -1L;
        }
    }

    /** Reads a Redis counter without turning a missing key into a cache failure. */
    public long getLong(String key) {
        if (!isRedisAvailable()) {
            return -1L;
        }
        try {
            String value = valueCommands.get(cachePrefix + key);
            if (value == null || value.isBlank()) {
                return 0L;
            }
            return Long.parseLong(value);
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to read cache counter: %s", e.getMessage());
            return -1L;
        }
    }

    /** Decrements a distributed counter and removes it when no lease remains. */
    public long decrement(String key) {
        if (!isRedisAvailable()) {
            return -1L;
        }
        try {
            String fullKey = cachePrefix + key;
            long value = valueCommands.decr(fullKey);
            if (value <= 0L) {
                keyCommands.del(fullKey);
                return 0L;
            }
            return value;
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to decrement cache counter: %s", e.getMessage());
            return -1L;
        }
    }

    public void deletePattern(String pattern) {
        if (!isRedisAvailable()) {
            return;
        }
        try {
            KeyScanArgs args = new KeyScanArgs().match(cachePrefix + pattern);
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
            markUnavailable();
            LOG.warnf("Failed to delete cache pattern: %s", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return isRedisAvailable();
    }

    private boolean isRedisAvailable() {
        if (redisAvailable) {
            return true;
        }
        reconnectIfNeeded();
        return redisAvailable;
    }

    private void reconnectIfNeeded() {
        long now = System.currentTimeMillis();
        if (now < nextReconnectAtMillis) {
            return;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (redisAvailable || now < nextReconnectAtMillis) {
                return;
            }
            try {
                RedisDataSource redisDataSource = redisDataSourceInstance.get();
                valueCommands = redisDataSource.value(String.class);
                keyCommands = redisDataSource.key(String.class);
                redisAvailable = true;
                nextReconnectAtMillis = 0L;
                LOG.infof("Redis cache service initialized or recovered successfully");
            } catch (Exception exception) {
                nextReconnectAtMillis = now + 5000L;
                LOG.warnf("Redis is unavailable; using local fallback until reconnect: %s",
                        exception.getMessage());
            }
        }
    }

    private void markUnavailable() {
        redisAvailable = false;
        nextReconnectAtMillis = System.currentTimeMillis() + 5000L;
    }

    public static class Keys {
        public static final String SIDEBAR_RECENT_POSTS = "sidebar:v2:recentPosts:";
        public static final String SIDEBAR_CATEGORIES = "sidebar:v2:categories:";
        public static final String SIDEBAR_TAGS = "sidebar:v2:tags:";
        public static final String SIDEBAR_STATS = "sidebar:v2:stats";
        // Versioned after moving the homepage to a public list read model.
        public static final String INDEX_PAGE_PREFIX = "index:v2:page:";
        public static final String PUBLIC_FEED_PREFIX = "feed:v2:";
        // Versioned after moving public article rendering to an explicit read model.
        public static final String POST_META_PREFIX = "post:meta:v4:";
        public static final String MEDIA_META_PREFIX = "media:meta:v2:";
        
        public static final String ALL_CATEGORIES = "categories:all";
        public static final String ALL_TAGS = "tags:all";
        public static final String TAG_POST_COUNT_PREFIX = "tags:postCount:";
        public static final String CATEGORY_POST_COUNT_PREFIX = "categories:postCount:";
        public static final String ALL_POSTS = "posts:all";

        public static String indexPage(int page, String lang) {
            return INDEX_PAGE_PREFIX + page + ":lang:" + lang;
        }

        public static String recentPosts(String lang, String region, int limit) {
            return SIDEBAR_RECENT_POSTS + lang + ":" + region + ":" + limit;
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

        public static String publicFeed(String lang, String region, int limit) {
            return PUBLIC_FEED_PREFIX + lang + ":" + region + ":" + limit;
        }

        public static String mediaMeta(String storageKey) {
            return MEDIA_META_PREFIX + storageKey;
        }
    }
}
