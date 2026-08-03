package com.biliwind.blog.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Publishes bounded local-storage gauges without scanning the media tree. */
@ApplicationScoped
public class StorageMetricsScheduler {

    private static final Logger LOG = Logger.getLogger(StorageMetricsScheduler.class);

    @ConfigProperty(name = "media.upload.dir", defaultValue = "uploads")
    String mediaUploadDirectory;

    @ConfigProperty(name = "windblog.storage.metrics.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    SecurityMetricsService securityMetricsService;

    @Scheduled(every = "60s", identity = "storage-metrics")
    void refresh() {
        if (!enabled) {
            return;
        }
        try {
            Path path = Paths.get(mediaUploadDirectory).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                securityMetricsService.setGauge("storage.media.path_exists", 0L);
                return;
            }
            java.nio.file.FileStore fileStore = Files.getFileStore(path);
            securityMetricsService.setGauge("storage.media.path_exists", 1L);
            securityMetricsService.setGauge("storage.media.bytes_total", fileStore.getTotalSpace());
            securityMetricsService.setGauge("storage.media.bytes_usable", fileStore.getUsableSpace());
            securityMetricsService.setGauge("storage.media.bytes_unallocated", fileStore.getUnallocatedSpace());
        } catch (Exception exception) {
            LOG.warnf("无法刷新媒体磁盘指标: %s", exception.getMessage());
            securityMetricsService.setGauge("storage.media.path_exists", 0L);
        }
    }
}
