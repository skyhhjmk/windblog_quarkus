package com.biliwind.blog.service;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class LogCompressionService {

    private static final Logger LOG = Logger.getLogger(LogCompressionService.class);
    private static final String ACTIVE_APPLICATION_LOG_FILE_NAME = "application.log";
    private static final String APPLICATION_LOG_PREFIX = "application.log.";
    private static final String BUFFER_LOG_PREFIX = "buffered-logs-";
    private static final String GZIP_SUFFIX = ".gz";

    @ConfigProperty(name = "windblog.log.root", defaultValue = "logs")
    String logRootDirectory;

    @ConfigProperty(name = "windblog.log.compression.enabled", defaultValue = "true")
    boolean compressionEnabled;

    @ConfigProperty(name = "windblog.log.compression.min-age-hours", defaultValue = "24")
    int minAgeHours;

    @ConfigProperty(name = "windblog.log.compression.retention-days", defaultValue = "180")
    int retentionDays;

    void onStart(@Observes StartupEvent ignored) {
        compressRotatedLogs();
    }

    @Scheduled(every = "12h", delayed = "15m", identity = "log-compression")
    void compressRotatedLogsOnSchedule() {
        compressRotatedLogs();
    }

    void compressRotatedLogs() {
        if (!compressionEnabled) {
            return;
        }

        Path logRootPath = Path.of(logRootDirectory);
        if (!Files.exists(logRootPath)) {
            return;
        }

        Instant now = Instant.now();
        Instant compressBefore = now.minus(Duration.ofHours(minAgeHours));
        Instant deleteBefore = now.minus(Duration.ofDays(retentionDays));

        try {
            scanDirectory(logRootPath, compressBefore, deleteBefore);
        } catch (Exception e) {
            LOG.warnf("压缩日志文件失败: %s", e.getMessage());
        }
    }

    private void scanDirectory(Path directoryPath, Instant compressBefore, Instant deleteBefore) throws IOException {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directoryPath)) {
            for (Path filePath : directoryStream) {
                if (Files.isDirectory(filePath)) {
                    scanDirectory(filePath, compressBefore, deleteBefore);
                    continue;
                }

                processFile(filePath, compressBefore, deleteBefore);
            }
        }
    }

    private void processFile(Path filePath, Instant compressBefore, Instant deleteBefore) throws IOException {
        String fileName = filePath.getFileName().toString();
        BasicFileAttributes fileAttributes = Files.readAttributes(filePath, BasicFileAttributes.class);

        if (fileName.endsWith(GZIP_SUFFIX)) {
            if (fileAttributes.lastModifiedTime().toInstant().isBefore(deleteBefore)) {
                Files.deleteIfExists(filePath);
            }
            return;
        }

        if (ACTIVE_APPLICATION_LOG_FILE_NAME.equals(fileName)) {
            return;
        }

        if (fileAttributes.lastModifiedTime().toInstant().isAfter(compressBefore)) {
            return;
        }

        if (fileName.startsWith(APPLICATION_LOG_PREFIX) || fileName.startsWith(BUFFER_LOG_PREFIX)) {
            compressFile(filePath);
        }
    }

    private void compressFile(Path sourceFilePath) {
        Path gzipFilePath = sourceFilePath.resolveSibling(sourceFilePath.getFileName().toString() + GZIP_SUFFIX);
        Path temporaryGzipFilePath = sourceFilePath.resolveSibling(gzipFilePath.getFileName().toString() + ".tmp");

        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(sourceFilePath));
             OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(temporaryGzipFilePath));
             GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int readCount = inputStream.read(buffer);
                if (readCount < 0) {
                    break;
                }
                gzipOutputStream.write(buffer, 0, readCount);
            }
        } catch (IOException e) {
            LOG.warnf("压缩日志文件失败: %s, 错误: %s", sourceFilePath, e.getMessage());
            try {
                Files.deleteIfExists(temporaryGzipFilePath);
            } catch (IOException ignored) {
                // 保留原始日志文件，避免压缩失败导致日志丢失
            }
            return;
        }

        try {
            Files.move(temporaryGzipFilePath, gzipFilePath, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(sourceFilePath);
            LOG.infof("已压缩日志文件: %s", gzipFilePath.getFileName());
        } catch (IOException e) {
            LOG.warnf("替换压缩日志文件失败: %s, 错误: %s", sourceFilePath, e.getMessage());
            try {
                Files.deleteIfExists(temporaryGzipFilePath);
            } catch (IOException ignored) {
                // 忽略清理失败，下一轮任务会重新处理
            }
        }
    }
}
