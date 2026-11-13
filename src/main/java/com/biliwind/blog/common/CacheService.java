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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

@ApplicationScoped
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);
    private static final String CACHE_PREFIX = "windblog:cache:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);
    private static final Duration LOAD_LOCK_TTL = Duration.ofSeconds(10);
    private static final long LOAD_LOCK_RETRY_MILLIS = 50L;
    private static final int LOAD_LOCK_RETRIES = 20;
    private static final String NEGATIVE_SUFFIX = ":absent";
    private static final String LOAD_LOCK_SUFFIX = ":lock";

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
            valueCommands.set(cachePrefix + key, json, new SetArgs().ex(withJitter(ttl).toSeconds()));
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to set cache value: %s", e.getMessage());
        }
    }

    /**
     * Cache-aside read through one seam: serve a hit, negative-cache an
     * authoritative miss, and let a single loader rebuild a missing key while
     * the other callers wait for it. Falls back to the loader when Redis is
     * unavailable, so callers never need a Redis-specific branch.
     */
    public <T> Optional<T> getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return getOrLoad(key, k -> get(k, type), ttl, loader);
    }

    public <T> Optional<T> getOrLoad(String key, TypeReference<T> typeRef, Duration ttl, Supplier<T> loader) {
        return getOrLoad(key, k -> get(k, typeRef), ttl, loader);
    }

    private <T> Optional<T> getOrLoad(String key, Function<String, Optional<T>> reader, Duration ttl,
                                      Supplier<T> loader) {
        Optional<T> cached = reader.apply(key);
        if (cached.isPresent()) {
            return cached;
        }
        if (!isRedisAvailable()) {
            return Optional.ofNullable(loader.get());
        }
        if (hasNegativeMarker(key)) {
            return Optional.empty();
        }

        String lockKey = key + LOAD_LOCK_SUFFIX;
        boolean lockHeld = acquireLoadLock(lockKey);
        if (!lockHeld) {
            for (int attempt = 0; attempt < LOAD_LOCK_RETRIES; attempt++) {
                // Redis just went away: do not make the request wait for a lock
                // nobody can release.
                if (!isRedisAvailable()) {
                    break;
                }
                sleepFor(LOAD_LOCK_RETRY_MILLIS);
                cached = reader.apply(key);
                if (cached.isPresent()) {
                    return cached;
                }
                if (hasNegativeMarker(key)) {
                    return Optional.empty();
                }
            }
            // The holder may have died; load instead of failing the request.
        }

        try {
            T value = loader.get();
            if (value == null) {
                writeNegativeMarker(key);
                return Optional.empty();
            }
            set(key, value, ttl);
            return Optional.of(value);
        } finally {
            if (lockHeld) {
                releaseLoadLock(lockKey);
            }
        }
    }

    private boolean acquireLoadLock(String lockKey) {
        try {
            return valueCommands.setAndChanged(cachePrefix + lockKey, "1",
                    new SetArgs().nx().ex(LOAD_LOCK_TTL.toSeconds()));
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to acquire cache load lock: %s", e.getMessage());
            return false;
        }
    }

    private void releaseLoadLock(String lockKey) {
        try {
            keyCommands.del(cachePrefix + lockKey);
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to release cache load lock: %s", e.getMessage());
        }
    }

    private boolean hasNegativeMarker(String key) {
        try {
            return keyCommands.exists(cachePrefix + key + NEGATIVE_SUFFIX);
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to read cache negative marker: %s", e.getMessage());
            return false;
        }
    }

    private void writeNegativeMarker(String key) {
        try {
            valueCommands.set(cachePrefix + key + NEGATIVE_SUFFIX, "1",
                    new SetArgs().ex(NEGATIVE_TTL.toSeconds()));
        } catch (Exception e) {
            markUnavailable();
            LOG.warnf("Failed to write cache negative marker: %s", e.getMessage());
        }
    }

    private static void sleepFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Spreads expirations by +/-10% so a warm cache cannot expire as one wave. */
    static Duration withJitter(Duration ttl) {
        return withJitter(ttl, ThreadLocalRandom.current());
    }

    static Duration withJitter(Duration ttl, RandomGenerator random) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return ttl;
        }
        long seconds = ttl.toSeconds();
        if (seconds <= 1L) {
            return ttl;
        }
        long spread = Math.max(1L, seconds / 10L);
        long offset = random.nextLong(-spread, spread + 1L);
        return Duration.ofSeconds(Math.max(1L, seconds + offset));
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
        // v3: earlier native builds serialized the sidebar view records without
        // reflection metadata as empty objects, so the version bump discards
        // those poisoned entries instead of deserializing them into null fields.
        public static final String SIDEBAR_RECENT_POSTS = "sidebar:v3:recentPosts:";
        public static final String SIDEBAR_CATEGORIES = "sidebar:v3:categories:";
        public static final String SIDEBAR_TAGS = "sidebar:v3:tags:";
        public static final String SIDEBAR_STATS = "sidebar:v3:stats";
        // Versioned after moving the homepage to a public list read model.
        public static final String INDEX_PAGE_PREFIX = "index:v2:page:";
        public static final String PUBLIC_FEED_PREFIX = "feed:v2:";
        // Versioned after moving public article rendering to an explicit read model.
        // v7: public preview content is now sanitized, fully rendered HTML rather
        // than markdown with pre-sanitization template fragments.
        public static final String POST_META_PREFIX = "post:meta:v7:";
        public static final String MEDIA_META_PREFIX = "media:meta:v2:";
        public static final String SEARCH_CACHE_PREFIX = "search:v1:";
        public static final String ARTICLE_MAP_RELATED_PREFIX = "article-map:related:v1:";
        // Public category/tag read models; lists are page+language, details add
        // the reader region because their post lists are region-filtered.
        public static final String PUBLIC_CATEGORY_LIST_PREFIX = "category:v1:list:";
        public static final String PUBLIC_TAG_LIST_PREFIX = "tag:v1:list:";
        public static final String PUBLIC_CATEGORY_DETAIL_PREFIX = "category:v1:detail:";
        public static final String PUBLIC_TAG_DETAIL_PREFIX = "tag:v1:detail:";
        // Sitemaps are rebuilt from a 1000-post page that re-renders every body,
        // so the crawler-facing pages are read through one shared prefix.
        public static final String SITEMAP_PREFIX = "sitemap:v1:";
        // The link page scans the whole table on every request and is filtered
        // per reader region, so the visible list is cached per region.
        public static final String PUBLIC_LINKS_PREFIX = "link:v1:public:";

        public static String categoryListPage(int page, String lang) {
            return PUBLIC_CATEGORY_LIST_PREFIX + lang + ":" + page;
        }

        public static String tagListPage(int page, String lang) {
            return PUBLIC_TAG_LIST_PREFIX + lang + ":" + page;
        }

        public static String categoryDetail(String slug, String lang, String region, int page) {
            return PUBLIC_CATEGORY_DETAIL_PREFIX + lang + ":" + region + ":" + page + ":" + slug;
        }

        public static String tagDetail(String slug, String lang, String region, int page) {
            return PUBLIC_TAG_DETAIL_PREFIX + lang + ":" + region + ":" + page + ":" + slug;
        }

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

        public static String searchResults(String queryHash) {
            return SEARCH_CACHE_PREFIX + queryHash;
        }

        public static String articleMapRelated(Long postId, String language, int limit) {
            return ARTICLE_MAP_RELATED_PREFIX + postId + ":" + language + ":" + limit;
        }

        public static String sitemapPostPages(String region) {
            return SITEMAP_PREFIX + "post-pages:" + region;
        }

        public static String sitemapPostUrls(String region, int page) {
            return SITEMAP_PREFIX + "post-urls:" + region + ":" + page;
        }

        public static String publicLinks(String region) {
            return PUBLIC_LINKS_PREFIX + region;
        }
    }
}
