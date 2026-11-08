package com.biliwind.blog.service;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages post reaction metrics with Redis cache and local in-memory fallback.
 */
@ApplicationScoped
public class ReactionService {

    private static final Logger LOG = Logger.getLogger(ReactionService.class);

    public static final List<String> ALLOWED_REACTIONS = List.of(
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
                } else {
                    // Seed initial realistic baseline in Redis so it's persisted immediately
                    Map<String, Long> seeded = new LinkedHashMap<>();
                    for (String type : ALLOWED_REACTIONS) {
                        seeded.put(type, seedCount(cleanSlug, type));
                    }
                    redis.hash(Long.class).hset(key, seeded);
                    return seeded;
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
                    Map<String, Long> seeded = new LinkedHashMap<>();
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

    public static int getSlugSeed(String slug) {
        if (slug == null || slug.isBlank()) {
            return 42;
        }
        int h = 0;
        for (int i = 0; i < slug.length(); i++) {
            h = 31 * h + slug.charAt(i);
        }
        return Math.abs(h & 0x7fffffff);
    }

    public static long seedCount(String slug, String type) {
        int seed = getSlugSeed(slug);
        return switch (type) {
            case "like" -> 16 + (seed % 23);             // 16 ~ 38
            case "heart" -> 11 + ((seed / 7) % 18);        // 11 ~ 28
            case "insightful" -> 6 + ((seed / 13) % 14);   // 6 ~ 19
            case "rocket" -> 3 + ((seed / 19) % 12);       // 3 ~ 14
            case "celebrate" -> 2 + ((seed / 29) % 10);    // 2 ~ 11
            default -> 5 + (seed % 10);
        };
    }
}
