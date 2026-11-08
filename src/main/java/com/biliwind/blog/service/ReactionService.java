package com.biliwind.blog.service;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages post reaction metrics with Redis cache and local in-memory fallback.
 */
@ApplicationScoped
public class ReactionService {

    private static final Logger LOG = Logger.getLogger(ReactionService.class);

    public static final Set<String> ALLOWED_REACTIONS = Set.of(
            "like", "heart", "rocket", "celebrate", "insightful"
    );

    private static final String REDIS_KEY_PREFIX = "windblog:post:reactions:";

    @Inject
    RedisDataSource redis;

    private final Map<String, Map<String, AtomicLong>> localStore = new ConcurrentHashMap<>();

    public Map<String, Long> getReactions(String slug) {
        String cleanSlug = sanitizeSlug(slug);
        Map<String, Long> result = new LinkedHashMap<>();

        if (redis != null) {
            try {
                String key = REDIS_KEY_PREFIX + cleanSlug;
                Map<String, Long> existing = redis.hash(Long.class).hgetall(key);
                if (existing != null && !existing.isEmpty()) {
                    for (String type : ALLOWED_REACTIONS) {
                        result.put(type, existing.getOrDefault(type, seedCount(cleanSlug, type)));
                    }
                    return result;
                }
            } catch (Exception e) {
                LOG.debugf("Redis reactions fetch error, falling back to local: %s", e.getMessage());
            }
        }

        Map<String, AtomicLong> local = localStore.computeIfAbsent(cleanSlug, k -> new ConcurrentHashMap<>());
        for (String type : ALLOWED_REACTIONS) {
            long count = local.computeIfAbsent(type, t -> new AtomicLong(seedCount(cleanSlug, t))).get();
            result.put(type, count);
        }
        return result;
    }

    public Map<String, Long> updateReaction(String slug, String reactionType, String action) {
        String cleanSlug = sanitizeSlug(slug);
        if (!ALLOWED_REACTIONS.contains(reactionType)) {
            throw new IllegalArgumentException("不支持的互动反应类型: " + reactionType);
        }

        long delta = "unreact".equalsIgnoreCase(action) ? -1L : 1L;

        if (redis != null) {
            try {
                String key = REDIS_KEY_PREFIX + cleanSlug;
                if (!redis.key().exists(key)) {
                    Map<String, Long> seeded = new HashMap<>();
                    for (String t : ALLOWED_REACTIONS) {
                        seeded.put(t, seedCount(cleanSlug, t));
                    }
                    redis.hash(Long.class).hset(key, seeded);
                }
                long updatedCount = redis.hash(Long.class).hincrby(key, reactionType, delta);
                if (updatedCount < 0) {
                    redis.hash(Long.class).hset(key, reactionType, 0L);
                }
                return getReactions(cleanSlug);
            } catch (Exception e) {
                LOG.debugf("Redis reactions update error, falling back to local: %s", e.getMessage());
            }
        }

        Map<String, AtomicLong> local = localStore.computeIfAbsent(cleanSlug, k -> new ConcurrentHashMap<>());
        for (String t : ALLOWED_REACTIONS) {
            local.putIfAbsent(t, new AtomicLong(seedCount(cleanSlug, t)));
        }
        AtomicLong counter = local.get(reactionType);
        long val = counter.addAndGet(delta);
        if (val < 0) {
            counter.set(0);
        }
        return getReactions(cleanSlug);
    }

    public static String sanitizeSlug(String slug) {
        return slug == null ? "" : slug.trim().replaceAll("[^a-zA-Z0-9_\\-]", "");
    }

    public static long seedCount(String slug, String type) {
        int hash = (slug + ":" + type).hashCode();
        return Math.abs(hash % 16) + 3;
    }
}
