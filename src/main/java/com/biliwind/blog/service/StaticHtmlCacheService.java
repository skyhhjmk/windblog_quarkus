package com.biliwind.blog.service;

import com.biliwind.blog.model.StaticCacheStat;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Full-site filesystem static HTML cache.
 * Stores rendered anonymous public HTML pages (both full documents and PJAX
 * fragments) as real .html files on disk, buffers hit/miss counters in Redis,
 * and flushes aggregated statistics to PostgreSQL asynchronously.
 */
@ApplicationScoped
public class StaticHtmlCacheService {

    private static final Logger LOG = Logger.getLogger(StaticHtmlCacheService.class);

    private static final Set<String> ALLOWED_MODES = Set.of("all", "standard", "tutorial", "resource");
    private static final String REDIS_PENDING_HITS_KEY = "windblog:static_cache:pending_hits";
    private static final String REDIS_PENDING_MISSES_KEY = "windblog:static_cache:pending_misses";
    private static final String REDIS_PENDING_LAST_HIT_KEY = "windblog:static_cache:pending_last_hit";

    @ConfigProperty(name = "windblog.static-cache.enabled", defaultValue = "true")
    boolean configuredEnabled;

    @ConfigProperty(name = "windblog.static-cache.dir", defaultValue = "data/static-cache")
    String cacheDirConfig;

    @Inject
    Instance<RedisDataSource> redisInstance;

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> localPendingHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> localPendingMisses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> localPendingLastHit = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> persistedHitsFallback = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> persistedMissesFallback = new ConcurrentHashMap<>();

    private Path rootDir;
    private Path pagesDir;
    private Path metaDir;

    @PostConstruct
    void init() {
        enabled.set(configuredEnabled);
        initDirectories(Paths.get(cacheDirConfig).toAbsolutePath().normalize());
        loadExistingEntries();
    }

    /** Visible for unit tests. */
    void initForTest(Path tempDir, boolean isEnabled) {
        enabled.set(isEnabled);
        initDirectories(tempDir.toAbsolutePath().normalize());
        entries.clear();
        localPendingHits.clear();
        localPendingMisses.clear();
        localPendingLastHit.clear();
        persistedHitsFallback.clear();
        persistedMissesFallback.clear();
        loadExistingEntries();
    }

    private void initDirectories(Path root) {
        this.rootDir = root;
        this.pagesDir = root.resolve("pages");
        this.metaDir = root.resolve("meta");
        try {
            Files.createDirectories(pagesDir);
            Files.createDirectories(metaDir);
        } catch (IOException e) {
            LOG.warnf("Failed to create static cache directories under %s: %s", root, e.getMessage());
        }
    }

    private void loadExistingEntries() {
        if (metaDir == null || !Files.isDirectory(metaDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(metaDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".properties"))
                    .forEach(this::loadMetaFile);
        } catch (IOException e) {
            LOG.warnf("Failed to scan static cache metadata: %s", e.getMessage());
        }
    }

    private void loadMetaFile(Path metaFile) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        String hash = props.getProperty("hash");
        String key = props.getProperty("key");
        String path = props.getProperty("path");
        if (hash == null || key == null || path == null) {
            return;
        }
        Path htmlFile = pagesDir.resolve(hash + ".html");
        if (!Files.isRegularFile(htmlFile)) {
            try {
                Files.deleteIfExists(metaFile);
            } catch (IOException ignored) {
            }
            return;
        }
        long sizeBytes = parseLong(props.getProperty("sizeBytes"), 0L);
        long createdAt = parseLong(props.getProperty("createdAt"), System.currentTimeMillis());
        long lastHitAt = parseLong(props.getProperty("lastHitAt"), 0L);
        long hits = parseLong(props.getProperty("hits"), 0L);
        String lang = props.getProperty("lang", "zh-CN");
        String region = props.getProperty("region", "DEFAULT");
        String variant = props.getProperty("variant", "full");
        String etag = props.getProperty("etag", "");

        CacheEntry entry = new CacheEntry(hash, key, path, lang, region, variant, etag, sizeBytes, createdAt);
        entry.persistedHits.set(hits);
        entry.lastHitAt.set(lastHitAt);
        entries.put(key, entry);
        if (hits > 0) {
            persistedHitsFallback.merge(key, hits, Long::sum);
        }
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public String getCacheDirPath() {
        return rootDir == null ? cacheDirConfig : rootDir.toString();
    }

    /**
     * Normalizes a request path + query string into a canonical cacheable path,
     * or returns null if the route/query should not be statically cached.
     */
    public String normalizeCacheablePath(String rawPath, Map<String, List<String>> queryParams) {
        if (rawPath == null || rawPath.isBlank()) {
            rawPath = "/";
        }
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (!isCacheableRoute(path)) {
            return null;
        }

        if (queryParams != null && !queryParams.isEmpty()) {
            for (String paramName : queryParams.keySet()) {
                if ("lang".equals(paramName)) {
                    continue;
                }
                if ("mode".equals(paramName) && (path.equals("/") || path.matches("^/page/[0-9]+$"))) {
                    continue;
                }
                return null;
            }
            if (path.equals("/") || path.matches("^/page/[0-9]+$")) {
                List<String> modes = queryParams.get("mode");
                if (modes != null && !modes.isEmpty()) {
                    String mode = modes.getFirst() == null ? "" : modes.getFirst().trim().toLowerCase(Locale.ROOT);
                    if (!mode.isEmpty() && !"all".equals(mode)) {
                        if (!ALLOWED_MODES.contains(mode)) {
                            return null;
                        }
                        return path + "?mode=" + mode;
                    }
                }
            }
        }
        return path;
    }

    static boolean isCacheableRoute(String path) {
        if ("/".equals(path) || path.matches("^/page/[0-9]+$")) {
            return true;
        }
        if (path.startsWith("/post/") && path.indexOf('/', 6) < 0) {
            return true;
        }
        if ("/categories".equals(path) || path.matches("^/categories/page/[0-9]+$")
                || "/category".equals(path) || path.matches("^/category/[^/]+(?:/page/[0-9]+)?$")) {
            return true;
        }
        if ("/tags".equals(path) || path.matches("^/tags/page/[0-9]+$")
                || "/tag".equals(path) || path.matches("^/tag/[^/]+(?:/page/[0-9]+)?$")) {
            return true;
        }
        if ("/link".equals(path) || "/links".equals(path)
                || path.matches("^/link/[0-9]+$")) {
            return true;
        }
        if ("/tools".equals(path) || path.matches("^/tools/[a-z0-9-]+$")) {
            return true;
        }
        return "/terms".equals(path) || "/privacy".equals(path)
                || "/legal/terms".equals(path) || "/legal/privacy".equals(path);
    }

    public String buildKey(String lang, String region, String normalizedPath) {
        return buildKey(lang, region, "full", normalizedPath);
    }

    public String buildKey(String lang, String region, String variant, String normalizedPath) {
        String safeLang = (lang == null || lang.isBlank()) ? "zh-CN" : lang.trim();
        String safeRegion = (region == null || region.isBlank()) ? "DEFAULT" : region.trim();
        String safeVariant = (variant == null || variant.isBlank()) ? "full" : variant.trim();
        return safeLang + "|" + safeRegion + "|" + safeVariant + "|" + normalizedPath;
    }

    public CachedDocument getIfPresent(String key) {
        if (!enabled.get() || key == null) {
            return null;
        }
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        Path htmlFile = pagesDir.resolve(entry.hash + ".html");
        try {
            if (!Files.isRegularFile(htmlFile)) {
                entries.remove(key, entry);
                return null;
            }
            byte[] bytes = Files.readAllBytes(htmlFile);
            long now = System.currentTimeMillis();
            entry.lastHitAt.set(now);
            recordHitAsync(key, now);
            return new CachedDocument(bytes, entry.etag, entry.path, entry.variant, bytes.length);
        } catch (IOException e) {
            LOG.debugf("Failed to read cached HTML file %s: %s", htmlFile, e.getMessage());
            return null;
        }
    }

    public void recordMiss() {
        recordMiss("__global__");
    }

    public void recordMiss(String key) {
        if (key == null || key.isBlank()) {
            key = "__global__";
        }
        RedisDataSource redis = resolveRedis();
        if (redis != null) {
            try {
                redis.hash(Long.class).hincrby(REDIS_PENDING_MISSES_KEY, key, 1L);
                return;
            } catch (Exception e) {
                LOG.debugf("Redis pending miss HINCRBY error: %s", e.getMessage());
            }
        }
        localPendingMisses.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private void recordHitAsync(String key, long nowMillis) {
        RedisDataSource redis = resolveRedis();
        if (redis != null) {
            try {
                redis.hash(Long.class).hincrby(REDIS_PENDING_HITS_KEY, key, 1L);
                redis.hash(Long.class).hset(REDIS_PENDING_LAST_HIT_KEY, key, nowMillis);
                return;
            } catch (Exception e) {
                LOG.debugf("Redis pending hit HINCRBY error: %s", e.getMessage());
            }
        }
        localPendingHits.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        localPendingLastHit.put(key, nowMillis);
    }

    public void put(String key, String path, String lang, String region, String html, String etag) {
        put(key, path, lang, region, "full", html, etag);
    }

    public void put(String key, String path, String lang, String region, String variant, String html, String etag) {
        if (!enabled.get() || key == null || html == null) {
            return;
        }
        String hash = sha256(key);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String resolvedEtag = (etag == null || etag.isBlank())
                ? "W/\"" + sha256(html).substring(0, 16) + "\""
                : etag;

        Path htmlFile = pagesDir.resolve(hash + ".html");
        Path metaFile = metaDir.resolve(hash + ".properties");
        try {
            Files.createDirectories(pagesDir);
            Files.createDirectories(metaDir);
            writeAtomically(htmlFile, bytes);

            long now = System.currentTimeMillis();
            CacheEntry existing = entries.get(key);
            long previousHits = existing != null ? existing.persistedHits.get() : 0L;
            long previousLastHit = existing != null ? existing.lastHitAt.get() : 0L;

            CacheEntry entry = new CacheEntry(hash, key, path, lang, region, variant, resolvedEtag, bytes.length, now);
            entry.persistedHits.set(previousHits);
            entry.lastHitAt.set(previousLastHit);
            entries.put(key, entry);
            writeMetaFile(metaFile, entry);
        } catch (IOException e) {
            LOG.warnf("Failed to write static HTML cache for %s: %s", path, e.getMessage());
        }
    }

    public boolean invalidateByKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        CacheEntry removed = entries.remove(key);
        if (removed == null) {
            return false;
        }
        deleteEntryFiles(removed);
        return true;
    }

    public int invalidateByPathPrefix(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return 0;
        }
        String normalized = pathPrefix.startsWith("/") ? pathPrefix : "/" + pathPrefix;
        int count = 0;
        for (Map.Entry<String, CacheEntry> item : entries.entrySet()) {
            CacheEntry entry = item.getValue();
            if (entry.path.equals(normalized) || entry.path.startsWith(normalized)) {
                if (entries.remove(item.getKey(), entry)) {
                    deleteEntryFiles(entry);
                    count++;
                }
            }
        }
        return count;
    }

    public int invalidateAll() {
        int removedCount = entries.size();
        entries.clear();
        clearDirectory(pagesDir);
        clearDirectory(metaDir);
        return removedCount;
    }

    public StaticCacheStatus getStatusSnapshot() {
        Map<String, Long> pendingHits = readPendingMap(REDIS_PENDING_HITS_KEY, localPendingHits);
        Map<String, Long> pendingMisses = readPendingMap(REDIS_PENDING_MISSES_KEY, localPendingMisses);
        Map<String, Long> dbHits = loadPersistedHitsFromDb();

        List<StaticCacheEntryView> list = new ArrayList<>(entries.size());
        long totalSize = 0L;
        long totalHits = 0L;
        for (CacheEntry entry : entries.values()) {
            totalSize += entry.sizeBytes;
            long baseHits = Math.max(entry.persistedHits.get(), dbHits.getOrDefault(entry.key, 0L));
            long entryHits = baseHits + pendingHits.getOrDefault(entry.key, 0L);
            totalHits += entryHits;
            list.add(new StaticCacheEntryView(
                    entry.key,
                    entry.path,
                    entry.lang,
                    entry.region,
                    entry.variant,
                    entry.sizeBytes,
                    entryHits,
                    Instant.ofEpochMilli(entry.createdAt).toString(),
                    entry.lastHitAt.get() > 0 ? Instant.ofEpochMilli(entry.lastHitAt.get()).toString() : null
            ));
        }
        // Include hits from any evicted entries that are still recorded in DB/pending
        for (Map.Entry<String, Long> item : dbHits.entrySet()) {
            if (!entries.containsKey(item.getKey())) {
                totalHits += item.getValue();
            }
        }
        for (Map.Entry<String, Long> item : pendingHits.entrySet()) {
            if (!entries.containsKey(item.getKey())) {
                totalHits += item.getValue();
            }
        }

        long totalMisses = loadPersistedMissesTotalFromDb();
        for (long v : pendingMisses.values()) {
            totalMisses += v;
        }

        list.sort(Comparator.comparingLong(StaticCacheEntryView::hits).reversed()
                .thenComparing(StaticCacheEntryView::path));

        long totalRequests = totalHits + totalMisses;
        double hitRate = totalRequests > 0 ? (totalHits * 100.0) / totalRequests : 0.0;

        return new StaticCacheStatus(
                enabled.get(),
                getCacheDirPath(),
                entries.size(),
                totalSize,
                totalHits,
                totalMisses,
                Math.round(hitRate * 100.0) / 100.0,
                list
        );
    }

    @Scheduled(every = "60s", identity = "static-html-cache-stats-sync")
    public void syncStatsToDatabase() {
        Map<String, Long> hitDeltas = drainPendingMap(REDIS_PENDING_HITS_KEY, localPendingHits);
        Map<String, Long> missDeltas = drainPendingMap(REDIS_PENDING_MISSES_KEY, localPendingMisses);
        Map<String, Long> lastHitTimes = drainLastHitMap();

        if (hitDeltas.isEmpty() && missDeltas.isEmpty()) {
            return;
        }

        try {
            persistStatsDeltas(hitDeltas, missDeltas, lastHitTimes);
        } catch (Exception e) {
            LOG.debugf("Static cache stats DB sync skipped/deferred: %s", e.getMessage());
            // Keep in fallback memory if DB is not available (e.g. unit test)
            hitDeltas.forEach((k, v) -> persistedHitsFallback.merge(k, v, Long::sum));
            missDeltas.forEach((k, v) -> persistedMissesFallback.merge(k, v, Long::sum));
        }

        // Update in-memory entry persisted counts & sidecar metadata
        for (Map.Entry<String, Long> item : hitDeltas.entrySet()) {
            CacheEntry entry = entries.get(item.getKey());
            if (entry != null) {
                entry.persistedHits.addAndGet(item.getValue());
                Long lastHit = lastHitTimes.get(item.getKey());
                if (lastHit != null && lastHit > entry.lastHitAt.get()) {
                    entry.lastHitAt.set(lastHit);
                }
                if (metaDir != null) {
                    try {
                        writeMetaFile(metaDir.resolve(entry.hash + ".properties"), entry);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    @Transactional
    void persistStatsDeltas(Map<String, Long> hitDeltas,
                            Map<String, Long> missDeltas,
                            Map<String, Long> lastHitTimes) {
        Set<String> allKeys = new java.util.HashSet<>(hitDeltas.keySet());
        allKeys.addAll(missDeltas.keySet());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        for (String key : allKeys) {
            long hDelta = hitDeltas.getOrDefault(key, 0L);
            long mDelta = missDeltas.getOrDefault(key, 0L);
            if (hDelta <= 0 && mDelta <= 0) {
                continue;
            }
            StaticCacheStat row = StaticCacheStat.findById(key);
            CacheEntry entry = entries.get(key);
            if (row == null) {
                row = new StaticCacheStat();
                row.cacheKey = key;
                row.path = entry != null ? entry.path : extractPathFromKey(key);
                row.lang = entry != null ? entry.lang : extractPartFromKey(key, 0, "zh-CN");
                row.region = entry != null ? entry.region : extractPartFromKey(key, 1, "DEFAULT");
                row.variant = entry != null ? entry.variant : extractPartFromKey(key, 2, "full");
                row.hits = hDelta;
                row.misses = mDelta;
                Long lastHit = lastHitTimes.get(key);
                row.lastHitAt = lastHit != null && lastHit > 0
                        ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(lastHit), ZoneOffset.UTC)
                        : null;
                row.updatedAt = now;
                row.persist();
            } else {
                row.hits += hDelta;
                row.misses += mDelta;
                Long lastHit = lastHitTimes.get(key);
                if (lastHit != null && lastHit > 0) {
                    row.lastHitAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(lastHit), ZoneOffset.UTC);
                }
                row.updatedAt = now;
            }
        }
    }

    @PreDestroy
    void onShutdown() {
        syncStatsToDatabase();
    }

    private Map<String, Long> loadPersistedHitsFromDb() {
        Map<String, Long> result = new HashMap<>(persistedHitsFallback);
        try {
            List<StaticCacheStat> rows = StaticCacheStat.listAll();
            for (StaticCacheStat row : rows) {
                result.put(row.cacheKey, Math.max(result.getOrDefault(row.cacheKey, 0L), row.hits));
            }
        } catch (Exception ignored) {
            // Unit test or DB unavailable
        }
        return result;
    }

    private long loadPersistedMissesTotalFromDb() {
        long fallbackSum = 0L;
        for (long v : persistedMissesFallback.values()) {
            fallbackSum += v;
        }
        try {
            long dbSum = 0L;
            List<StaticCacheStat> rows = StaticCacheStat.listAll();
            for (StaticCacheStat row : rows) {
                dbSum += row.misses;
            }
            return Math.max(dbSum, fallbackSum);
        } catch (Exception ignored) {
            return fallbackSum;
        }
    }

    private Map<String, Long> readPendingMap(String redisKey, ConcurrentHashMap<String, AtomicLong> localMap) {
        Map<String, Long> result = new HashMap<>();
        RedisDataSource redis = resolveRedis();
        if (redis != null) {
            try {
                Map<String, Long> all = redis.hash(Long.class).hgetall(redisKey);
                if (all != null) {
                    all.forEach((k, v) -> {
                        if (v != null && v > 0) {
                            result.merge(k, v, Long::sum);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }
        localMap.forEach((k, v) -> {
            long val = v.get();
            if (val > 0) {
                result.merge(k, val, Long::sum);
            }
        });
        return result;
    }

    private Map<String, Long> drainPendingMap(String redisKey, ConcurrentHashMap<String, AtomicLong> localMap) {
        Map<String, Long> drained = new HashMap<>();
        RedisDataSource redis = resolveRedis();
        if (redis != null) {
            try {
                HashCommands<String, String, Long> hash = redis.hash(Long.class);
                Map<String, Long> all = hash.hgetall(redisKey);
                if (all != null) {
                    for (Map.Entry<String, Long> entry : all.entrySet()) {
                        long delta = entry.getValue() == null ? 0L : entry.getValue();
                        if (delta > 0) {
                            long remaining = hash.hincrby(redisKey, entry.getKey(), -delta);
                            if (remaining <= 0) {
                                hash.hdel(redisKey, entry.getKey());
                            }
                            drained.merge(entry.getKey(), delta, Long::sum);
                        } else {
                            hash.hdel(redisKey, entry.getKey());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (String k : List.copyOf(localMap.keySet())) {
            AtomicLong counter = localMap.get(k);
            if (counter != null) {
                long delta = counter.getAndSet(0L);
                if (delta > 0) {
                    drained.merge(k, delta, Long::sum);
                }
            }
        }
        return drained;
    }

    private Map<String, Long> drainLastHitMap() {
        Map<String, Long> result = new HashMap<>();
        RedisDataSource redis = resolveRedis();
        if (redis != null) {
            try {
                HashCommands<String, String, Long> hash = redis.hash(Long.class);
                Map<String, Long> all = hash.hgetall(REDIS_PENDING_LAST_HIT_KEY);
                if (all != null) {
                    for (Map.Entry<String, Long> entry : all.entrySet()) {
                        if (entry.getValue() != null) {
                            result.put(entry.getKey(), entry.getValue());
                        }
                        hash.hdel(REDIS_PENDING_LAST_HIT_KEY, entry.getKey());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (String k : List.copyOf(localPendingLastHit.keySet())) {
            Long val = localPendingLastHit.remove(k);
            if (val != null) {
                result.merge(k, val, Math::max);
            }
        }
        return result;
    }

    private RedisDataSource resolveRedis() {
        try {
            if (redisInstance != null && !redisInstance.isUnsatisfied()) {
                return redisInstance.get();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void writeMetaFile(Path metaFile, CacheEntry entry) throws IOException {
        Properties props = new Properties();
        props.setProperty("hash", entry.hash);
        props.setProperty("key", entry.key);
        props.setProperty("path", entry.path);
        props.setProperty("lang", entry.lang);
        props.setProperty("region", entry.region);
        props.setProperty("variant", entry.variant);
        props.setProperty("etag", entry.etag);
        props.setProperty("sizeBytes", Long.toString(entry.sizeBytes));
        props.setProperty("createdAt", Long.toString(entry.createdAt));
        props.setProperty("lastHitAt", Long.toString(entry.lastHitAt.get()));
        props.setProperty("hits", Long.toString(entry.persistedHits.get()));

        Path temp = Files.createTempFile(metaDir, entry.hash, ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, "WindBlog Static HTML Cache Metadata");
        }
        Files.move(temp, metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteEntryFiles(CacheEntry entry) {
        try {
            Files.deleteIfExists(pagesDir.resolve(entry.hash + ".html"));
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(metaDir.resolve(entry.hash + ".properties"));
        } catch (IOException ignored) {
        }
    }

    private void clearDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String extractPathFromKey(String key) {
        if (key == null) return "/";
        int lastSep = key.lastIndexOf('|');
        return lastSep >= 0 && lastSep + 1 < key.length() ? key.substring(lastSep + 1) : key;
    }

    private static String extractPartFromKey(String key, int index, String fallback) {
        if (key == null) return fallback;
        String[] parts = key.split("\\|", 4);
        return index < parts.length && !parts[index].isBlank() ? parts[index] : fallback;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class CacheEntry {
        final String hash;
        final String key;
        final String path;
        final String lang;
        final String region;
        final String variant;
        final String etag;
        final long sizeBytes;
        final long createdAt;
        final AtomicLong persistedHits = new AtomicLong(0);
        final AtomicLong lastHitAt = new AtomicLong(0);

        CacheEntry(String hash, String key, String path, String lang, String region,
                   String variant, String etag, long sizeBytes, long createdAt) {
            this.hash = hash;
            this.key = key;
            this.path = path;
            this.lang = lang == null ? "zh-CN" : lang;
            this.region = region == null ? "DEFAULT" : region;
            this.variant = variant == null ? "full" : variant;
            this.etag = etag == null ? "" : etag;
            this.sizeBytes = sizeBytes;
            this.createdAt = createdAt;
        }
    }

    public record CachedDocument(byte[] html, String etag, String path, String variant, long sizeBytes) {}

    @RegisterForReflection
    public record StaticCacheEntryView(
            String key,
            String path,
            String lang,
            String region,
            String variant,
            long sizeBytes,
            long hits,
            String createdAt,
            String lastHitAt
    ) {}

    @RegisterForReflection
    public record StaticCacheStatus(
            boolean enabled,
            String cacheDir,
            int entryCount,
            long totalSizeBytes,
            long totalHits,
            long totalMisses,
            double hitRate,
            List<StaticCacheEntryView> entries
    ) {}
}
