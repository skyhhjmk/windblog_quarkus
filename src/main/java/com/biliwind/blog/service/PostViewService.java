package com.biliwind.blog.service;

import com.biliwind.blog.service.security.SecurityRateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步文章浏览量统计与查询服务。
 * 使用 Redis 聚合增量（HINCRBY），并通过定时任务与停机钩子一次性批量写入 PostgreSQL。
 */
@ApplicationScoped
public class PostViewService {

    private static final Logger LOG = Logger.getLogger(PostViewService.class);
    private static final String REDIS_BASE_KEY_PREFIX = "windblog:post:views:base:";
    private static final String REDIS_PENDING_HASH_KEY = "windblog:post:views:pending";
    private static final Duration BASE_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration VIEW_DEDUP_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_BATCH_SLUGS = 100;

    @Inject
    EntityManager entityManager;

    @Inject
    RedisDataSource redis;

    @Inject
    SecurityRateLimitService rateLimitService;

    @Inject
    ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, AtomicLong> localPendingDeltas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> localBaseCounts = new ConcurrentHashMap<>();

    /**
     * 记录一次文章浏览（带 IP 时间窗口去重），增量聚合到 Redis（或本地内存兜底），不直接写库。
     */
    public Optional<Long> recordView(String slug, String clientIp) {
        String cleanSlug = sanitizeSlug(slug);
        if (cleanSlug.isEmpty()) {
            return Optional.empty();
        }

        Optional<Long> baseOpt = resolveBaseCount(cleanSlug);
        if (baseOpt.isEmpty()) {
            return Optional.empty();
        }
        long baseCount = baseOpt.get();

        boolean shouldIncrement = true;
        if (rateLimitService != null && clientIp != null && !clientIp.isBlank()) {
            String rateKey = "post-view:" + cleanSlug + ":" + clientIp.trim();
            shouldIncrement = rateLimitService.tryAcquire(rateKey, 1, VIEW_DEDUP_WINDOW);
        }

        if (!shouldIncrement) {
            return Optional.of(baseCount + readPendingDelta(cleanSlug));
        }

        long pendingDelta = incrementPendingDelta(cleanSlug);
        return Optional.of(Math.max(0L, baseCount + pendingDelta));
    }

    /**
     * 批量查询文章实时浏览量（数据库基数 + Redis 待刷盘增量）。
     */
    public Map<String, Long> getViewCounts(Collection<String> slugs) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (slugs == null || slugs.isEmpty()) {
            return result;
        }

        Set<String> cleanSlugs = new LinkedHashSet<>();
        for (String raw : slugs) {
            String clean = sanitizeSlug(raw);
            if (!clean.isEmpty()) {
                cleanSlugs.add(clean);
                if (cleanSlugs.size() >= MAX_BATCH_SLUGS) {
                    break;
                }
            }
        }
        if (cleanSlugs.isEmpty()) {
            return result;
        }

        Map<String, Long> baseCounts = new LinkedHashMap<>();
        Set<String> missingSlugs = new LinkedHashSet<>();
        for (String cleanSlug : cleanSlugs) {
            Long cached = readCachedBaseCount(cleanSlug);
            if (cached != null) {
                baseCounts.put(cleanSlug, cached);
            } else {
                missingSlugs.add(cleanSlug);
            }
        }

        if (!missingSlugs.isEmpty()) {
            Map<String, Long> dbCounts = fetchFromDatabase(missingSlugs);
            for (Map.Entry<String, Long> entry : dbCounts.entrySet()) {
                baseCounts.put(entry.getKey(), entry.getValue());
                cacheBaseCount(entry.getKey(), entry.getValue());
            }
        }

        Map<String, Long> pendingMap = readPendingDeltas(baseCounts.keySet());
        for (Map.Entry<String, Long> entry : baseCounts.entrySet()) {
            String slug = entry.getKey();
            long pending = pendingMap.getOrDefault(slug, 0L);
            result.put(slug, Math.max(0L, entry.getValue() + pending));
        }

        return result;
    }

    public Optional<Long> getViewCount(String slug) {
        String cleanSlug = sanitizeSlug(slug);
        if (cleanSlug.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> baseOpt = resolveBaseCount(cleanSlug);
        if (baseOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Math.max(0L, baseOpt.get() + readPendingDelta(cleanSlug)));
    }

    /**
     * 定时将 Redis 中聚合的浏览量增量一次性批量写入数据库。
     */
    @Scheduled(every = "30s", identity = "post-views-flush",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledFlushPendingViews() {
        flushPendingViews();
    }

    @PreDestroy
    void onShutdown() {
        try {
            flushPendingViews();
        } catch (Exception e) {
            LOG.warnf("Shutdown flush of post views failed: %s", e.getMessage());
        }
    }

    /**
     * 排空 Redis 与本地兜底缓存中的待写入增量，并通过单条 SQL 一次性批量更新数据库。
     */
    public int flushPendingViews() {
        Map<String, Long> deltasToFlush = drainPendingDeltas();
        if (deltasToFlush.isEmpty()) {
            return 0;
        }

        try {
            Map<String, Long> updatedTotals = batchUpdateDatabase(deltasToFlush);
            for (Map.Entry<String, Long> entry : updatedTotals.entrySet()) {
                cacheBaseCount(entry.getKey(), entry.getValue());
            }
            return updatedTotals.size();
        } catch (Exception e) {
            LOG.warnf("Batch flush of post views failed, restoring %d pending entries: %s",
                    deltasToFlush.size(), e.getMessage());
            restorePendingDeltas(deltasToFlush);
            return 0;
        }
    }

    @Transactional
    protected Map<String, Long> batchUpdateDatabase(Map<String, Long> deltas) throws Exception {
        Map<String, Long> updatedTotals = new LinkedHashMap<>();
        if (entityManager == null || deltas == null || deltas.isEmpty()) {
            return updatedTotals;
        }
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        String deltasJson = mapper.writeValueAsString(deltas);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "UPDATE posts AS p "
                                + "SET view_count = COALESCE(p.view_count, 0) + (v.value)::bigint "
                                + "FROM jsonb_each_text(CAST(:deltasJson AS jsonb)) AS v(slug, value) "
                                + "WHERE p.slug = v.slug AND p.deleted_at IS NULL "
                                + "RETURNING p.slug, p.view_count")
                .setParameter("deltasJson", deltasJson)
                .getResultList();

        for (Object[] row : rows) {
            if (row != null && row[0] != null && row[1] != null) {
                updatedTotals.put(row[0].toString(), Math.max(0L, ((Number) row[1]).longValue()));
            }
        }
        return updatedTotals;
    }

    private Optional<Long> resolveBaseCount(String cleanSlug) {
        Long cached = readCachedBaseCount(cleanSlug);
        if (cached != null) {
            return Optional.of(cached);
        }
        Map<String, Long> dbCounts = fetchFromDatabase(List.of(cleanSlug));
        Long dbCount = dbCounts.get(cleanSlug);
        if (dbCount == null) {
            return Optional.empty();
        }
        cacheBaseCount(cleanSlug, dbCount);
        return Optional.of(dbCount);
    }

    private long incrementPendingDelta(String cleanSlug) {
        if (redis != null) {
            try {
                return redis.hash(Long.class).hincrby(REDIS_PENDING_HASH_KEY, cleanSlug, 1L);
            } catch (Exception e) {
                LOG.debugf("Redis pending view HINCRBY error for %s: %s", cleanSlug, e.getMessage());
            }
        }
        return localPendingDeltas.computeIfAbsent(cleanSlug, k -> new AtomicLong(0L)).incrementAndGet();
    }

    private long readPendingDelta(String cleanSlug) {
        long local = 0L;
        AtomicLong localCounter = localPendingDeltas.get(cleanSlug);
        if (localCounter != null) {
            local = Math.max(0L, localCounter.get());
        }
        if (redis != null) {
            try {
                Long val = redis.hash(Long.class).hget(REDIS_PENDING_HASH_KEY, cleanSlug);
                return Math.max(0L, (val == null ? 0L : val) + local);
            } catch (Exception e) {
                LOG.debugf("Redis pending view HGET error for %s: %s", cleanSlug, e.getMessage());
            }
        }
        return local;
    }

    private Map<String, Long> readPendingDeltas(Collection<String> slugs) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (slugs == null || slugs.isEmpty()) {
            return result;
        }
        if (redis != null) {
            try {
                HashCommands<String, String, Long> hash = redis.hash(Long.class);
                Map<String, Long> redisValues = hash.hmget(REDIS_PENDING_HASH_KEY, slugs.toArray(String[]::new));
                if (redisValues != null) {
                    redisValues.forEach((slug, val) -> {
                        if (val != null && val > 0) {
                            result.put(slug, val);
                        }
                    });
                }
            } catch (Exception e) {
                LOG.debugf("Redis pending view HMGET error: %s", e.getMessage());
            }
        }
        for (String slug : slugs) {
            AtomicLong localCounter = localPendingDeltas.get(slug);
            if (localCounter != null && localCounter.get() > 0) {
                result.merge(slug, localCounter.get(), Long::sum);
            }
        }
        return result;
    }

    private Map<String, Long> drainPendingDeltas() {
        Map<String, Long> drained = new LinkedHashMap<>();

        if (redis != null) {
            try {
                HashCommands<String, String, Long> hash = redis.hash(Long.class);
                Map<String, Long> allPending = hash.hgetall(REDIS_PENDING_HASH_KEY);
                if (allPending != null && !allPending.isEmpty()) {
                    for (Map.Entry<String, Long> entry : allPending.entrySet()) {
                        String slug = entry.getKey();
                        long delta = entry.getValue() == null ? 0L : entry.getValue();
                        if (delta > 0) {
                            long remaining = hash.hincrby(REDIS_PENDING_HASH_KEY, slug, -delta);
                            if (remaining <= 0) {
                                hash.hdel(REDIS_PENDING_HASH_KEY, slug);
                            }
                            drained.merge(slug, delta, Long::sum);
                        } else if (delta <= 0) {
                            hash.hdel(REDIS_PENDING_HASH_KEY, slug);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debugf("Redis pending view drain error: %s", e.getMessage());
            }
        }

        for (String slug : List.copyOf(localPendingDeltas.keySet())) {
            AtomicLong counter = localPendingDeltas.get(slug);
            if (counter != null) {
                long delta = counter.getAndSet(0L);
                if (delta > 0) {
                    drained.merge(slug, delta, Long::sum);
                }
            }
        }

        return drained;
    }

    private void restorePendingDeltas(Map<String, Long> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : deltas.entrySet()) {
            long delta = entry.getValue() == null ? 0L : entry.getValue();
            if (delta <= 0) {
                continue;
            }
            if (redis != null) {
                try {
                    redis.hash(Long.class).hincrby(REDIS_PENDING_HASH_KEY, entry.getKey(), delta);
                    continue;
                } catch (Exception e) {
                    LOG.debugf("Redis pending view restore error for %s: %s", entry.getKey(), e.getMessage());
                }
            }
            localPendingDeltas.computeIfAbsent(entry.getKey(), k -> new AtomicLong(0L)).addAndGet(delta);
        }
    }

    private Map<String, Long> fetchFromDatabase(Collection<String> slugs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (entityManager == null || slugs == null || slugs.isEmpty()) {
            return counts;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT p.slug, p.viewCount FROM Post p "
                                + "WHERE p.slug IN :slugs AND p.deletedAt IS NULL")
                .setParameter("slugs", slugs)
                .getResultList();
        for (Object[] row : rows) {
            if (row != null && row[0] != null) {
                long count = row[1] == null ? 0L : ((Number) row[1]).longValue();
                counts.put(row[0].toString(), Math.max(0L, count));
            }
        }
        return counts;
    }

    private Long readCachedBaseCount(String cleanSlug) {
        if (redis != null) {
            try {
                Long value = redis.value(Long.class).get(REDIS_BASE_KEY_PREFIX + cleanSlug);
                if (value != null && value >= 0) {
                    return value;
                }
            } catch (Exception e) {
                LOG.debugf("Redis base viewCount read error for %s: %s", cleanSlug, e.getMessage());
            }
        }
        return localBaseCounts.get(cleanSlug);
    }

    private void cacheBaseCount(String cleanSlug, long count) {
        long safeCount = Math.max(0L, count);
        localBaseCounts.put(cleanSlug, safeCount);
        if (redis != null) {
            try {
                redis.value(Long.class).setex(REDIS_BASE_KEY_PREFIX + cleanSlug,
                        BASE_CACHE_TTL.toSeconds(), safeCount);
            } catch (Exception e) {
                LOG.debugf("Redis base viewCount write error for %s: %s", cleanSlug, e.getMessage());
            }
        }
    }

    private String sanitizeSlug(String slug) {
        if (slug == null) {
            return "";
        }
        String trimmed = slug.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            return "";
        }
        return trimmed;
    }
}
