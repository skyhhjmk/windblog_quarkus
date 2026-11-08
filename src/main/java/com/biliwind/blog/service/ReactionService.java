package com.biliwind.blog.service;

import com.biliwind.blog.model.PostReaction;
import com.biliwind.blog.repository.PostReactionRepository;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages post reaction metrics with PostgreSQL database durability,
 * high-performance Redis cache-aside, and in-memory fallback.
 */
@ApplicationScoped
public class ReactionService {

    private static final Logger LOG = Logger.getLogger(ReactionService.class);

    public static final List<String> ALLOWED_REACTIONS = List.of(
            "like", "heart", "rocket", "celebrate", "insightful"
    );

    private static final String REDIS_KEY_PREFIX = "windblog:post:reactions:";

    @Inject
    PostReactionRepository reactionRepository;

    @Inject
    RedisDataSource redis;

    private final Map<String, Map<String, AtomicLong>> localStore = new ConcurrentHashMap<>();

    public Map<String, Long> getReactions(String slug) {
        String cleanSlug = sanitizeSlug(slug);

        // 1. Fast cache read from Redis
        if (redis != null) {
            try {
                String key = REDIS_KEY_PREFIX + cleanSlug;
                Map<String, Long> existing = redis.hash(Long.class).hgetall(key);
                if (existing != null && !existing.isEmpty()) {
                    Map<String, Long> result = new LinkedHashMap<>();
                    for (String type : ALLOWED_REACTIONS) {
                        result.put(type, existing.getOrDefault(type, seedCount(cleanSlug, type)));
                    }
                    return result;
                }
            } catch (Exception e) {
                LOG.debugf("Redis reactions fetch error: %s", e.getMessage());
            }
        }

        // 2. Primary durable storage in PostgreSQL database
        if (reactionRepository != null) {
            return loadOrInitFromDatabase(cleanSlug);
        }

        // 3. Fallback to in-memory local store
        return loadOrInitLocal(cleanSlug);
    }

    @Transactional
    public Map<String, Long> loadOrInitFromDatabase(String cleanSlug) {
        try {
            Optional<PostReaction> opt = reactionRepository.findBySlug(cleanSlug);
            PostReaction pr;
            if (opt.isPresent()) {
                pr = opt.get();
            } else {
                pr = new PostReaction();
                pr.postSlug = cleanSlug;
                for (String t : ALLOWED_REACTIONS) {
                    pr.setCount(t, seedCount(cleanSlug, t));
                }
                reactionRepository.persist(pr);
            }
            Map<String, Long> map = pr.toMap();
            syncToRedis(cleanSlug, map);
            return map;
        } catch (Exception e) {
            LOG.warnf("Database reactions load/init error for slug %s: %s", cleanSlug, e.getMessage());
            return loadOrInitLocal(cleanSlug);
        }
    }

    @Transactional
    public Map<String, Long> updateReaction(String slug, String reactionType, String action) {
        String cleanSlug = sanitizeSlug(slug);
        if (!ALLOWED_REACTIONS.contains(reactionType)) {
            throw new IllegalArgumentException("不支持的互动反应类型: " + reactionType);
        }

        long delta = "unreact".equalsIgnoreCase(action) ? -1L : 1L;

        // 1. Update in PostgreSQL database
        if (reactionRepository != null) {
            try {
                Optional<PostReaction> opt = reactionRepository.findBySlug(cleanSlug);
                PostReaction pr;
                if (opt.isPresent()) {
                    pr = opt.get();
                } else {
                    pr = new PostReaction();
                    pr.postSlug = cleanSlug;
                    for (String t : ALLOWED_REACTIONS) {
                        pr.setCount(t, seedCount(cleanSlug, t));
                    }
                }
                pr.applyDelta(reactionType, delta);
                pr.updatedAt = OffsetDateTime.now();
                reactionRepository.persist(pr);

                Map<String, Long> map = pr.toMap();
                syncToRedis(cleanSlug, map);
                return map;
            } catch (Exception e) {
                LOG.warnf("Database reactions update error for slug %s: %s", cleanSlug, e.getMessage());
            }
        }

        // 2. Fallback to Redis atomic increment + local store
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

    private void syncToRedis(String cleanSlug, Map<String, Long> counts) {
        if (redis != null) {
            try {
                String key = REDIS_KEY_PREFIX + cleanSlug;
                redis.hash(Long.class).hset(key, counts);
            } catch (Exception e) {
                LOG.debugf("Redis sync error: %s", e.getMessage());
            }
        }
    }

    private Map<String, Long> loadOrInitLocal(String cleanSlug) {
        Map<String, Long> result = new LinkedHashMap<>();
        Map<String, AtomicLong> local = localStore.computeIfAbsent(cleanSlug, k -> new ConcurrentHashMap<>());
        for (String type : ALLOWED_REACTIONS) {
            long count = local.computeIfAbsent(type, t -> new AtomicLong(seedCount(cleanSlug, t))).get();
            result.put(type, count);
        }
        return result;
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
