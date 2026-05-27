package com.biliwind.blog.service.elasticsearch;

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
import java.util.Optional;
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
    @ConfigProperty(name = "elasticsearch.hosts")
    String elasticsearchHosts;
    @ConfigProperty(name = "elasticsearch.username")
    Optional<String> username;
    @ConfigProperty(name = "elasticsearch.password")
    Optional<String> password;
    @ConfigProperty(name = "elasticsearch.health-check.interval-seconds", defaultValue = "30")
    int healthCheckIntervalSeconds;
    @ConfigProperty(name = "elasticsearch.health-check.failure-threshold", defaultValue = "3")
    int healthCheckFailureThreshold;
    @ConfigProperty(name = "elasticsearch.health-check.recovery-threshold", defaultValue = "2")
    int healthCheckRecoveryThreshold;
    @ConfigProperty(name = "elasticsearch.health-check.timeout-seconds", defaultValue = "5")
    int healthCheckTimeoutSeconds;
    @ConfigProperty(name = "elasticsearch.init.max-wait-seconds", defaultValue = "300")
    int maxWaitSeconds;
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

        log.debug("ElasticsearchConnectionManager startup initiated, starting async initialization...");
        healthStatus.set(HealthStatus.INITIALIZING);

        CompletableFuture.runAsync(this::asyncInitialize);

        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "es-health-check");
            t.setDaemon(true);
            return t;
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
            onAvailableCallbacks.add(callback);
            log.debugf("Current registered callback count: %d", onAvailableCallbacks.size());
        }
    }

    /**
     * Async initialize ElasticSearch connection
     */
    private void asyncInitialize() {
        log.debugf("Starting async connection to ElasticSearch: %s", elasticsearchHosts);
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
                    log.debug("All callbacks executed, clearing callback list");
                    onAvailableCallbacks.clear();
                    return;
                }
            } catch (Exception e) {
                lastError.set(e.getMessage());
                log.debugf("ElasticSearch connection attempt failed: %s", e.getMessage());
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
        try {
            recordHealthCheckResult(checkElasticsearchConnection(), null);
        } catch (Exception e) {
            recordHealthCheckResult(false, e.getMessage());
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
        onAvailableCallbacks.clear();
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
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/_cluster/health"))
                    .timeout(java.time.Duration.ofSeconds(Math.max(1, healthCheckTimeoutSeconds)))
                    .GET()
                    .build();

            var response = sendRequest(request);
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send HTTP request to ElasticSearch
     */
    public HttpResponse<String> sendRequest(HttpRequest request) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(request.timeout().orElse(java.time.Duration.ofSeconds(30)));

        request.headers().map().forEach((name, values) -> {
            for (String value : values) {
                builder.header(name, value);
            }
        });

        String configuredUsername = "";
        if (username != null && username.isPresent()) {
            configuredUsername = username.get();
        }
        String configuredPassword = "";
        if (password != null && password.isPresent()) {
            configuredPassword = password.get();
        }

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
        return available.get();
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
        return new ConnectionStatus(
                initialized.get(),
                available.get(),
                healthStatus.get(),
                lastError.get()
        );
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
            String lastError
    ) {
    }
}
