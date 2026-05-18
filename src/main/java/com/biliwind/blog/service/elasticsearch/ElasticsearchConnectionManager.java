package com.biliwind.blog.service.elasticsearch;

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
    private final CopyOnWriteArrayList<Runnable> onAvailableCallbacks = new CopyOnWriteArrayList<>();
    @Inject
    HttpClient httpClient;
    @ConfigProperty(name = "elasticsearch.hosts")
    String elasticsearchHosts;
    @ConfigProperty(name = "elasticsearch.username", defaultValue = "")
    String username;
    @ConfigProperty(name = "elasticsearch.password", defaultValue = "")
    String password;
    @ConfigProperty(name = "elasticsearch.health-check.interval-seconds", defaultValue = "30")
    int healthCheckIntervalSeconds;
    @ConfigProperty(name = "elasticsearch.init.max-wait-seconds", defaultValue = "300")
    int maxWaitSeconds;
    private ScheduledExecutorService healthCheckExecutor;

    void onStart(@Observes StartupEvent event) {
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
            boolean wasAvailable = available.get();
            boolean nowAvailable = checkElasticsearchConnection();

            available.set(nowAvailable);

            if (nowAvailable && !wasAvailable) {
                healthStatus.set(HealthStatus.AVAILABLE);
                lastError.set(null);
                log.debug("ElasticSearch service recovered");

                // Execute registered callbacks
                for (Runnable callback : onAvailableCallbacks) {
                    try {
                        callback.run();
                    } catch (Exception e) {
                        log.error("Callback execution failed", e);
                    }
                }
                onAvailableCallbacks.clear();
            } else if (!nowAvailable && wasAvailable) {
                healthStatus.set(HealthStatus.UNAVAILABLE);
                log.warn("ElasticSearch service became unavailable");
            }
        } catch (Exception e) {
            available.set(false);
            healthStatus.set(HealthStatus.UNAVAILABLE);
            lastError.set(e.getMessage());
        }
    }

    /**
     * Check ElasticSearch connection
     */
    private boolean checkElasticsearchConnection() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/_cluster/health"))
                    .timeout(java.time.Duration.ofSeconds(5))
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

        if (username != null && !username.isEmpty() && password != null) {
            String auth = username + ":" + password;
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
