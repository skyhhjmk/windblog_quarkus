package com.biliwind.blog.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Periodically refreshes PostgreSQL table-level disk usage gauges. */
@ApplicationScoped
public class DatabaseStorageMetricsScheduler {

    private static final Logger LOG = Logger.getLogger(DatabaseStorageMetricsScheduler.class);

    @ConfigProperty(name = "windblog.db.metrics.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    DatabaseStorageMonitorService databaseStorageMonitorService;

    @Scheduled(every = "60s", identity = "database-storage-metrics")
    void refresh() {
        if (!enabled) {
            return;
        }
        try {
            databaseStorageMonitorService.collectSnapshot();
        } catch (Exception exception) {
            LOG.warnf("无法刷新数据库表级存储指标: %s", exception.getMessage());
        }
    }
}
