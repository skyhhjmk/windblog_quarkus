package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ElasticSearch connection manager
 * Responsible for async connection initialization, health check and status management
 */
@ApplicationScoped
public class ElasticsearchConnectionManager {

    private static final Logger log = Logger.getLogger(ElasticsearchConnectionManager.class);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<HealthStatus> healthStatus = new AtomicReference<>(HealthStatus.UNINITIALIZED);
    private final AtomicInteger consecutiveHealthCheckFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveHealthCheckSuccesses = new AtomicInteger(0);
    private final CopyOnWriteArrayList<Runnable> onAvailableCallbacks = new CopyOnWriteArrayList<>();
    @Inject
    HttpClient httpClient;
    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    ElasticsearchSettingsService settingsService;
    @ConfigProperty(name = "elasticsearch.health-check.interval-seconds", defaultValue = "30")
    int healthCheckIntervalSeconds;
    @ConfigProperty(name = "elasticsearch.health-check.failure-threshold", defaultValue = "3")
    int healthCheckFailureThreshold;
    @ConfigProperty(name = "elasticsearch.health-check.recovery-threshold", defaultValue = "2")
    int healthCheckRecoveryThreshold;
    @ConfigProperty(name = "elasticsearch.init.max-wait-seconds", defaultValue = "300")
    int maxWaitSeconds;
    private volatile ElasticsearchSettingsService.ElasticsearchSettings currentSettings;
    private ScheduledExecutorService healthCheckExecutor;

    void onStart(@Observes StartupEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            initialized.set(true);
            available.set(false);
            healthStatus.set(HealthStatus.DISABLED);
            lastError.set("边缘节点不启用 Elasticsearch");
            log.info("当前节点是边缘节点，跳过 Elasticsearch 连接初始化");
            return;
        }

        currentSettings = settingsService.getSettings();
        startHealthCheckExecutor();
        if (!currentSettings.enabled()) {
            initialized.set(true);
            available.set(false);
            healthStatus.set(HealthStatus.DISABLED);
            lastError.set("系统设置已关闭 Elasticsearch");
            log.info("系统设置已关闭 Elasticsearch，跳过连接初始化");
            return;
        }

        log.debug("ElasticsearchConnectionManager startup initiated, starting async initialization...");
        healthStatus.set(HealthStatus.INITIALIZING);

        CompletableFuture.runAsync(this::asyncInitialize);

    }

    private synchronized void startHealthCheckExecutor() {
        if (healthCheckExecutor != null) {
            return;
        }
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread healthCheckThread = new Thread(runnable, "es-health-check");
            healthCheckThread.setDaemon(true);
            return healthCheckThread;
        });
        healthCheckExecutor.scheduleWithFixedDelay(
                this::performHealthCheck,
                healthCheckIntervalSeconds,
                healthCheckIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    void destroy() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckExecutor.shutdownNow();
            }
        }
    }

    /**
     * Register callback for when Elasticsearch becomes available
     */
    public void onAvailable(Runnable callback) {
        String callbackName = callback.toString();
        log.debugf("onAvailable called, current available status: %s, callback count before: %d",
                available.get(), onAvailableCallbacks.size());
        log.debugf("Callback class: %s", callbackName);
        onAvailableCallbacks.addIfAbsent(callback);
        if (available.get()) {
            log.debug("Elasticsearch already available, executing callback immediately");
            try {
                callback.run();
                log.debug("Callback execution completed");
            } catch (Exception e) {
                log.error("Callback execution failed", e);
            }
        } else {
            log.debug("Elasticsearch not available, callback registered for later execution");
            log.debugf("Current registered callback count: %d", onAvailableCallbacks.size());
        }
    }

    /**
     * Async initialize ElasticSearch connection
     */
    private void asyncInitialize() {
        ElasticsearchSettingsService.ElasticsearchSettings settings = getCurrentSettings();
        if (!settings.enabled()) {
            markDisabled();
            return;
        }
        log.debugf("Starting async connection to ElasticSearch: %s", settings.hosts());
        long startTime = System.currentTimeMillis();
        long maxWaitMs = maxWaitSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            try {
                if (checkElasticsearchConnection()) {
                    initialized.set(true);
                    available.set(true);
                    healthStatus.set(HealthStatus.AVAILABLE);
                    lastError.set(null);
                    resetHealthCheckCounters();
                    log.debug("ElasticSearch connection initialized successfully");
                    log.debugf("Preparing to execute %d callbacks", onAvailableCallbacks.size());

                    // Execute registered callbacks
                    int callbackCount = 0;
                    for (Runnable callback : onAvailableCallbacks) {
                        try {
                            log.debugf("Executing callback #%d: %s", ++callbackCount, callback.toString());
                            callback.run();
                            log.debugf("Callback #%d completed: %s", callbackCount, callback.toString());
                        } catch (Exception e) {
                            log.errorf("Callback #%d failed: %s", callbackCount, callback.toString(), e);
                        }
                    }
                    // Keep callbacks registered so a later connection recovery
                    // can re-run index initialization and restore readiness.
                    log.debug("All Elasticsearch availability callbacks executed");
                    return;
                }
            } catch (Exception e) {
                String safeMessage = SensitiveMessageSanitizer.sanitize(e.getMessage());
                lastError.set(safeMessage);
                log.debugf("ElasticSearch connection attempt failed: %s", safeMessage);
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        healthStatus.set(HealthStatus.UNAVAILABLE);
        log.warn("ElasticSearch connection initialization timed out, service will run in degraded mode");
    }

    /**
     * Perform health check
     */
    private void performHealthCheck() {
        if (!getCurrentSettings().enabled()) {
            markDisabled();
            return;
        }
        try {
            recordHealthCheckResult(checkElasticsearchConnection(), null);
        } catch (Exception e) {
            recordHealthCheckResult(false, SensitiveMessageSanitizer.sanitize(e.getMessage()));
        }
    }

    void recordHealthCheckResult(boolean success, String errorMessage) {
        if (success) {
            int successes = consecutiveHealthCheckSuccesses.incrementAndGet();
            consecutiveHealthCheckFailures.set(0);

            if (!available.get() && successes < normalizedRecoveryThreshold()) {
                healthStatus.set(HealthStatus.DEGRADED);
                lastError.set(null);
                log.debugf("ElasticSearch health check recovered once, waiting for %d/%d successful checks",
                        successes, normalizedRecoveryThreshold());
                return;
            }

            boolean wasAvailable = available.getAndSet(true);
            healthStatus.set(HealthStatus.AVAILABLE);
            lastError.set(null);

            if (!wasAvailable) {
                log.info("ElasticSearch service recovered");
                executeAvailableCallbacks();
            }
            return;
        }

        int failures = consecutiveHealthCheckFailures.incrementAndGet();
        consecutiveHealthCheckSuccesses.set(0);
        if (errorMessage != null && !errorMessage.isBlank()) {
            lastError.set(errorMessage);
        }

        if (available.get() && failures < normalizedFailureThreshold()) {
            healthStatus.set(HealthStatus.DEGRADED);
            log.debugf("ElasticSearch health check failed %d/%d, keeping service available during transient pressure",
                    failures, normalizedFailureThreshold());
            return;
        }

        boolean wasAvailable = available.getAndSet(false);
        healthStatus.set(HealthStatus.UNAVAILABLE);
        if (wasAvailable) {
            log.warnf("ElasticSearch service became unavailable after %d consecutive failed health checks", failures);
        }
    }

    private void executeAvailableCallbacks() {
        for (Runnable callback : onAvailableCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("Callback execution failed", e);
            }
        }
        // Availability callbacks are persistent: services such as index
        // initialization must run again after a connection outage/recovery.
    }

    private void resetHealthCheckCounters() {
        consecutiveHealthCheckFailures.set(0);
        consecutiveHealthCheckSuccesses.set(0);
    }

    private int normalizedFailureThreshold() {
        return Math.max(1, healthCheckFailureThreshold);
    }

    private int normalizedRecoveryThreshold() {
        return Math.max(1, healthCheckRecoveryThreshold);
    }

    /**
     * Check ElasticSearch connection
     */
    private boolean checkElasticsearchConnection() {
        ElasticsearchSettingsService.ElasticsearchSettings settings = getCurrentSettings();
        if (!settings.enabled()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(resolveUri("/_cluster/health"))
                    .timeout(java.time.Duration.ofSeconds(settings.timeoutSeconds()))
                    .GET()
                    .build();

            HttpResponse<String> response = sendRequest(request);
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send HTTP request to ElasticSearch
     */
    public HttpResponse<String> sendRequest(HttpRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(request.timeout().orElse(java.time.Duration.ofSeconds(30)));

        request.headers().map().forEach((name, values) -> {
            for (String value : values) {
                builder.header(name, value);
            }
        });

        ElasticsearchSettingsService.ElasticsearchSettings settings = getCurrentSettings();
        String configuredUsername = settings.username();
        String configuredPassword = settings.password();

        if (configuredUsername != null && !configuredUsername.isEmpty() && configuredPassword != null) {
            String auth = configuredUsername + ":" + configuredPassword;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encodedAuth);
        }

        String method = request.method();
        if (method.equals("GET")) {
            builder.GET();
        } else if (method.equals("PUT")) {
            request.bodyPublisher().ifPresentOrElse(
                    builder::PUT,
                    () -> builder.PUT(HttpRequest.BodyPublishers.noBody())
            );
        } else if (method.equals("POST")) {
            request.bodyPublisher().ifPresentOrElse(
                    builder::POST,
                    () -> builder.POST(HttpRequest.BodyPublishers.noBody())
            );
        } else if (method.equals("DELETE")) {
            builder.DELETE();
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public boolean isAvailable() {
        return getCurrentSettings().enabled() && available.get();
    }

    public HealthStatus getHealthStatus() {
        return healthStatus.get();
    }

    public String getLastError() {
        return lastError.get();
    }

    public void markDegraded(String reason) {
        healthStatus.set(HealthStatus.DEGRADED);
        lastError.set(reason);
        log.warnf("ElasticSearch service marked as degraded: %s", reason);
    }

    public ConnectionStatus getStatus() {
        ElasticsearchSettingsService.ElasticsearchSettings settings = getCurrentSettings();
        return new ConnectionStatus(
                initialized.get(),
                settings.enabled() && available.get(),
                healthStatus.get(),
                lastError.get(),
                settings.enabled(),
                settings.hosts()
        );
    }

    public URI resolveUri(String path) {
        String normalizedPath = path;
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return URI.create(getCurrentSettings().hosts() + normalizedPath);
    }

    public boolean testConnection() {
        return checkElasticsearchConnection();
    }

    public ElasticsearchSettingsService.ElasticsearchSettings getCurrentSettings() {
        ElasticsearchSettingsService.ElasticsearchSettings settings = currentSettings;
        if (settings == null) {
            settings = settingsService.getSettings();
            currentSettings = settings;
        }
        return settings;
    }

    public void onConfigChanged(@Observes ConfigChangedEvent event) {
        if (!ElasticsearchSettingsService.SETTING_KEY.equals(event.key)) {
            return;
        }

        currentSettings = settingsService.parse(event.newValue);
        startHealthCheckExecutor();
        if (!currentSettings.enabled()) {
            markDisabled();
            return;
        }

        initialized.set(false);
        available.set(false);
        healthStatus.set(HealthStatus.INITIALIZING);
        lastError.set(null);
        CompletableFuture.runAsync(this::asyncInitialize);
    }

    private void markDisabled() {
        initialized.set(true);
        available.set(false);
        healthStatus.set(HealthStatus.DISABLED);
        lastError.set("系统设置已关闭 Elasticsearch");
        resetHealthCheckCounters();
    }

    public enum HealthStatus {
        UNINITIALIZED,
        DISABLED,
        INITIALIZING,
        AVAILABLE,
        DEGRADED,
        UNAVAILABLE
    }

    public record ConnectionStatus(
            boolean initialized,
            boolean available,
            HealthStatus status,
            String lastError,
            boolean enabled,
            String hosts
    ) {
    }
}
