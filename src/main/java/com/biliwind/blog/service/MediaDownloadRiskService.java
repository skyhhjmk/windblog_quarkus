package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Download budget shared by Redis-backed instances, with a bounded local fallback
 * for development and graceful operation while Redis is unavailable.
 */
@ApplicationScoped
public class MediaDownloadRiskService {

    private static final Duration SHORT_WINDOW = Duration.ofMinutes(1);
    private static final Duration LONG_WINDOW = Duration.ofMinutes(10);
    private static final int SUBJECT_PER_MINUTE = 30;
    private static final int IP_PER_MINUTE = 60;
    private static final int SUBJECT_PER_TEN_MINUTES = 120;
    private static final int TICKET_PER_TEN_MINUTES = 24;
    private static final int BEHAVIOR_MIN_SAMPLES = 20;
    private static final int BEHAVIOR_DENIED_PERCENT = 80;
    private static final int MAX_LOCAL_BEHAVIOR_WINDOWS = 4096;
    private static final long SUBJECT_BYTES_PER_TEN_MINUTES = 2L * 1024L * 1024L * 1024L;
    private static final long IP_BYTES_PER_TEN_MINUTES = 4L * 1024L * 1024L * 1024L;
    private static final long ARTICLE_BYTES_PER_TEN_MINUTES = 1L * 1024L * 1024L * 1024L;
    private static final int MAX_CONCURRENT_DOWNLOADS = 2;
    private static final long CONCURRENCY_LEASE_SECONDS = 15 * 60L;
    private static final long CONCURRENCY_BUCKET_MILLIS = CONCURRENCY_LEASE_SECONDS * 1000L;

    @ConfigProperty(name = "windblog.media.download.max-bytes", defaultValue = "536870912")
    long maxSingleDownloadBytes;

    @Inject
    CacheService cacheService;

    @Inject
    SecurityRateLimitService localRateLimitService;

    @Inject
    SecurityMetricsService securityMetricsService;

    private final Map<String, Map<String, Long>> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, BehaviorWindow> behaviorWindows = new ConcurrentHashMap<>();

    public Decision check(Long subjectId, Long postId, String clientIp) {
        return check(subjectId, postId, clientIp, null);
    }

    public Decision check(Long subjectId, Long postId, String clientIp, Long estimatedBytes) {
        return check(subjectId, postId, clientIp, estimatedBytes, null);
    }

    public Decision check(Long subjectId, Long postId, String clientIp,
                          Long estimatedBytes, Long ticketId) {
        if (estimatedBytes != null && estimatedBytes > maxSingleDownloadBytes) {
            securityMetricsService.increment("download.denied", "SIZE_LIMIT");
            return new Decision(false, "SIZE_LIMIT", 300);
        }
        String subjectKey = subjectId == null ? "anonymous" : String.valueOf(subjectId);
        String ipKey = digest(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        String behaviorKey = buildBehaviorKey(subjectId, ipKey);
        if (!isBehaviorAllowed(behaviorKey)) {
            securityMetricsService.increment("download.denied", "BEHAVIOR_4XX_RATIO_LIMIT");
            return new Decision(false, "BEHAVIOR_4XX_RATIO_LIMIT", 600);
        }
        recordBehaviorRequest(behaviorKey);

        if (ticketId != null && !allow(
                "media-download:ticket:" + digest(String.valueOf(ticketId)) + ":ten-minutes",
                TICKET_PER_TEN_MINUTES,
                LONG_WINDOW)) {
            securityMetricsService.increment("download.denied", "TICKET_BEHAVIOR_LIMIT");
            return new Decision(false, "TICKET_BEHAVIOR_LIMIT", 600);
        }

        if (estimatedBytes != null && estimatedBytes > 0) {
            if (!allowWeighted("media-download:subject:" + subjectKey + ":bytes:ten-minutes",
                    estimatedBytes, SUBJECT_BYTES_PER_TEN_MINUTES, LONG_WINDOW)) {
                securityMetricsService.increment("download.denied", "SUBJECT_BYTE_LIMIT");
                return new Decision(false, "SUBJECT_BYTE_LIMIT", 600);
            }
            if (!allowWeighted("media-download:ip:" + ipKey + ":bytes:ten-minutes",
                    estimatedBytes, IP_BYTES_PER_TEN_MINUTES, LONG_WINDOW)) {
                securityMetricsService.increment("download.denied", "IP_BYTE_LIMIT");
                return new Decision(false, "IP_BYTE_LIMIT", 600);
            }
            if (postId != null && !allowWeighted(
                    "media-download:subject:" + subjectKey + ":post:" + postId + ":bytes:ten-minutes",
                    estimatedBytes, ARTICLE_BYTES_PER_TEN_MINUTES, LONG_WINDOW)) {
                securityMetricsService.increment("download.denied", "ARTICLE_BYTE_LIMIT");
                return new Decision(false, "ARTICLE_BYTE_LIMIT", 600);
            }
        }

        if (!allow("media-download:subject:" + subjectKey + ":minute", SUBJECT_PER_MINUTE, SHORT_WINDOW)) {
            securityMetricsService.increment("download.denied", "SUBJECT_RATE_LIMIT");
            return new Decision(false, "SUBJECT_RATE_LIMIT", 60);
        }
        if (!allow("media-download:ip:" + ipKey + ":minute", IP_PER_MINUTE, SHORT_WINDOW)) {
            securityMetricsService.increment("download.denied", "IP_RATE_LIMIT");
            return new Decision(false, "IP_RATE_LIMIT", 60);
        }
        if (postId != null && !allow(
                "media-download:subject:" + subjectKey + ":post:" + postId + ":ten-minutes",
                SUBJECT_PER_TEN_MINUTES,
                LONG_WINDOW)) {
            securityMetricsService.increment("download.denied", "ARTICLE_RATE_LIMIT");
            return new Decision(false, "ARTICLE_RATE_LIMIT", 600);
        }
        securityMetricsService.increment("download.allowed");
        return new Decision(true, null, 0);
    }

    /** Records a client error after an authenticated ticket reached a 403/404 outcome. */
    public void recordClientError(Long subjectId, String clientIp, int statusCode) {
        if (statusCode != 403 && statusCode != 404) {
            return;
        }
        String ipKey = digest(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        recordBehaviorDenied(buildBehaviorKey(subjectId, ipKey));
    }

    /** Records a validated-ticket request that ended in a 403/404 before risk checks ran. */
    public void recordClientAttempt(Long subjectId, String clientIp, int statusCode) {
        if (statusCode != 403 && statusCode != 404) {
            return;
        }
        String ipKey = digest(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        String key = buildBehaviorKey(subjectId, ipKey);
        recordBehaviorRequest(key);
        recordBehaviorDenied(key);
    }

    public DownloadLease tryAcquireConcurrency(Long subjectId, Long postId, String clientIp) {
        String principal = subjectId == null ? digest(clientIp == null ? "unknown" : clientIp)
                : "subject:" + subjectId;
        String key = principal + ":post:" + (postId == null ? "unknown" : postId);
        if (cacheService.isAvailable()) {
            DownloadLease distributedLease = tryAcquireDistributedConcurrency(key);
            if (distributedLease != null) {
                return distributedLease;
            }
            if (cacheService.isAvailable()) {
                securityMetricsService.increment("download.denied", "CONCURRENT_LIMIT");
                return null;
            }
        }

        Map<String, Long> leases = activeDownloads.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        synchronized (leases) {
            leases.entrySet().removeIf(entry -> entry.getValue() < now);
            if (leases.size() >= MAX_CONCURRENT_DOWNLOADS) {
                securityMetricsService.increment("download.denied", "CONCURRENT_LIMIT");
                return null;
            }
            String leaseId = UUID.randomUUID().toString();
            leases.put(leaseId, now + CONCURRENCY_LEASE_SECONDS * 1000L);
            securityMetricsService.setGauge("download.active", activeLeaseCount());
            return new DownloadLease(key, leaseId, null);
        }
    }

    private DownloadLease tryAcquireDistributedConcurrency(String key) {
        long bucket = System.currentTimeMillis() / CONCURRENCY_BUCKET_MILLIS;
        String distributedKey = "media-download:concurrency:" + digest(key) + ":" + bucket;
        long count = cacheService.increment(distributedKey, Duration.ofSeconds(CONCURRENCY_LEASE_SECONDS));
        if (count < 0L) {
            return null;
        }
        if (count > MAX_CONCURRENT_DOWNLOADS) {
            cacheService.decrement(distributedKey);
            return null;
        }
        securityMetricsService.setGauge("download.active.distributed", count);
        return new DownloadLease(key, UUID.randomUUID().toString(), distributedKey);
    }

    public void releaseConcurrency(DownloadLease lease) {
        if (lease == null) {
            return;
        }
        if (lease.distributedKey() != null) {
            long count = cacheService.decrement(lease.distributedKey());
            if (count >= 0L) {
                securityMetricsService.setGauge("download.active.distributed", count);
            }
            return;
        }
        Map<String, Long> leases = activeDownloads.get(lease.key());
        if (leases == null) {
            return;
        }
        synchronized (leases) {
            leases.remove(lease.leaseId());
            if (leases.isEmpty()) {
                activeDownloads.remove(lease.key(), leases);
            }
            securityMetricsService.setGauge("download.active", activeLeaseCount());
        }
    }

    @Scheduled(every = "1m", identity = "media-download-concurrency-cleanup")
    void cleanExpiredConcurrencyLeases() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Map<String, Long>> entry : activeDownloads.entrySet()) {
            Map<String, Long> leases = entry.getValue();
            synchronized (leases) {
                leases.entrySet().removeIf(lease -> lease.getValue() < now);
                if (leases.isEmpty()) {
                    activeDownloads.remove(entry.getKey(), leases);
                }
            }
        }
        securityMetricsService.setGauge("download.active", activeLeaseCount());
    }

    @Scheduled(every = "1m", identity = "media-download-behavior-cleanup")
    void cleanExpiredBehaviorWindows() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, BehaviorWindow> entry : behaviorWindows.entrySet()) {
            BehaviorWindow window = entry.getValue();
            synchronized (window) {
                if (now - window.startedAt >= LONG_WINDOW.toMillis()) {
                    behaviorWindows.remove(entry.getKey(), window);
                }
            }
        }
    }

    private long activeLeaseCount() {
        long count = 0;
        for (Map<String, Long> leases : activeDownloads.values()) {
            count += leases.size();
        }
        return count;
    }

    private boolean allow(String key, int limit, Duration window) {
        if (cacheService.isAvailable()) {
            long count = cacheService.increment(key, window);
            if (count >= 0) {
                return count <= limit;
            }
        }
        return localRateLimitService.tryAcquire(key, limit, window);
    }

    private boolean allowWeighted(String key, long amount, long limit, Duration window) {
        if (amount < 1) {
            return true;
        }
        if (cacheService.isAvailable()) {
            long total = cacheService.incrementBy(key, amount, window);
            if (total >= 0) {
                return total <= limit;
            }
        }
        return localRateLimitService.tryAcquireWeighted(key, amount, limit, window);
    }

    private String buildBehaviorKey(Long subjectId, String ipKey) {
        String subjectKey = subjectId == null ? "anonymous" : String.valueOf(subjectId);
        return "media-download:behavior:" + subjectKey + ":" + ipKey;
    }

    private boolean isBehaviorAllowed(String key) {
        if (cacheService.isAvailable()) {
            long requests = cacheService.getLong(key + ":requests");
            long denied = cacheService.getLong(key + ":denied");
            if (requests >= 0 && denied >= 0) {
                return requests < BEHAVIOR_MIN_SAMPLES
                        || denied * 100L < requests * BEHAVIOR_DENIED_PERCENT;
            }
        }
        BehaviorWindow window = behaviorWindows.get(key);
        if (window == null) {
            return true;
        }
        synchronized (window) {
            resetBehaviorWindowIfExpired(window);
            return window.requests < BEHAVIOR_MIN_SAMPLES
                    || window.denied * 100L < window.requests * BEHAVIOR_DENIED_PERCENT;
        }
    }

    private void recordBehaviorRequest(String key) {
        if (cacheService.isAvailable()) {
            long value = cacheService.increment(key + ":requests", LONG_WINDOW);
            if (value >= 0) {
                return;
            }
        }
        BehaviorWindow window = getOrCreateLocalBehaviorWindow(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            resetBehaviorWindowIfExpired(window);
            window.requests = window.requests + 1;
        }
    }

    private void recordBehaviorDenied(String key) {
        if (cacheService.isAvailable()) {
            long value = cacheService.increment(key + ":denied", LONG_WINDOW);
            if (value >= 0) {
                return;
            }
        }
        BehaviorWindow window = getOrCreateLocalBehaviorWindow(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            resetBehaviorWindowIfExpired(window);
            window.denied = window.denied + 1;
        }
    }

    private BehaviorWindow getOrCreateLocalBehaviorWindow(String key) {
        synchronized (behaviorWindows) {
            BehaviorWindow existing = behaviorWindows.get(key);
            if (existing != null) {
                return existing;
            }
            if (behaviorWindows.size() >= MAX_LOCAL_BEHAVIOR_WINDOWS) {
                evictOldestLocalBehaviorWindow();
            }
            BehaviorWindow created = new BehaviorWindow(System.currentTimeMillis());
            behaviorWindows.put(key, created);
            return created;
        }
    }

    private void evictOldestLocalBehaviorWindow() {
        String oldestKey = null;
        BehaviorWindow oldestWindow = null;
        for (Map.Entry<String, BehaviorWindow> entry : behaviorWindows.entrySet()) {
            BehaviorWindow candidate = entry.getValue();
            synchronized (candidate) {
                if (oldestWindow == null || candidate.startedAt < oldestWindow.startedAt) {
                    oldestKey = entry.getKey();
                    oldestWindow = candidate;
                }
            }
        }
        if (oldestKey != null && oldestWindow != null) {
            behaviorWindows.remove(oldestKey, oldestWindow);
        }
    }

    private void resetBehaviorWindowIfExpired(BehaviorWindow window) {
        long now = System.currentTimeMillis();
        if (now - window.startedAt >= LONG_WINDOW.toMillis()) {
            window.startedAt = now;
            window.requests = 0;
            window.denied = 0;
        }
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成下载限流键", exception);
        }
    }

    private static class BehaviorWindow {
        private long startedAt;
        private long requests;
        private long denied;

        private BehaviorWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    public record Decision(boolean allowed, String reason, int retryAfterSeconds) {
    }

    public record DownloadLease(String key, String leaseId, String distributedKey) {
    }
}
