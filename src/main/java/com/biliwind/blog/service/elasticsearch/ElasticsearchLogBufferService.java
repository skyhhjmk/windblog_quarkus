package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ElasticSearch 日志缓冲服务
 * 当 ElasticSearch 不可用时，将日志缓存到本地文件
 * 当 ElasticSearch 恢复时，自动推送缓存的日志
 */
@ApplicationScoped
public class ElasticsearchLogBufferService {

    private static final Logger log = Logger.getLogger(ElasticsearchLogBufferService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final int DEFAULT_QUEUE_SIZE = 10000;
    private static final String WRITE_ALIAS = "windblog-logs";
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean canSendToEs = new AtomicBoolean(false);
    private final AtomicLong droppedLogs = new AtomicLong(0);
    private final AtomicLong bufferedLogs = new AtomicLong(0);
    private final AtomicLong sentLogs = new AtomicLong(0);
    @Inject
    ElasticsearchConnectionManager connectionManager;
    @ConfigProperty(name = "elasticsearch.log.buffer.dir", defaultValue = "logs/es-buffer")
    String bufferDir;
    @ConfigProperty(name = "elasticsearch.log.buffer.max-file-size-mb", defaultValue = "100")
    int maxFileSizeMb;
    @ConfigProperty(name = "elasticsearch.log.buffer.max-queue-size", defaultValue = "10000")
    int maxQueueSize;
    @ConfigProperty(name = "elasticsearch.log.flush.interval-seconds", defaultValue = "10")
    int flushIntervalSeconds;
    @ConfigProperty(name = "quarkus.log.handler.elasticsearch.hosts")
    String elasticsearchHosts;
    private BlockingQueue<LogEntry> logQueue;
    private ExecutorService flushExecutor;
    private ScheduledExecutorService scheduledExecutor;
    private Path bufferFilePath;

    @PostConstruct
    void init() {
        // 初始化队列（在配置注入后）
        int queueSize = maxQueueSize > 0 ? maxQueueSize : DEFAULT_QUEUE_SIZE;
        logQueue = new LinkedBlockingQueue<>(queueSize);
        log.infof("日志队列初始化完成，大小: %d", queueSize);

        try {
            Path dir = Paths.get(bufferDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            bufferFilePath = dir.resolve("buffered-logs.jsonl");
            log.infof("日志缓冲区目录: %s", bufferDir);
        } catch (IOException e) {
            log.error("初始化日志缓冲区失败", e);
        }

        flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "es-log-flush");
            t.setDaemon(true);
            return t;
        });

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "es-log-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 注册连接可用回调
        connectionManager.onAvailable(() -> {
            log.info("ElasticSearch 连接可用，5秒后尝试发送日志...");
            // 延迟一段时间确保索引已初始化
            scheduledExecutor.schedule(() -> {
                canSendToEs.set(true);
                log.info("日志发送已启用，开始刷新缓冲日志");
                tryFlushBufferedLogs();
            }, 5, TimeUnit.SECONDS);
        });

        scheduledExecutor.scheduleWithFixedDelay(
                this::tryFlushBufferedLogs,
                flushIntervalSeconds,
                flushIntervalSeconds,
                TimeUnit.SECONDS
        );

        loadExistingBufferedLogs();
    }

    @PreDestroy
    void destroy() {
        running.set(false);

        flushRemainingLogs();

        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
        if (flushExecutor != null) {
            flushExecutor.shutdown();
        }
    }

    /**
     * 缓冲日志条目
     * 如果 ElasticSearch 可用则直接发送，否则缓存到文件
     */
    public void bufferLog(String level, String message, String loggerName, String threadName, Throwable throwable) {
        LogEntry entry = new LogEntry(
                OffsetDateTime.now(),
                level,
                message,
                loggerName,
                threadName,
                throwable != null ? throwable.getMessage() : null
        );

        // 只有连接就绪且标记为可发送时才直接发送
        if (connectionManager.isAvailable() && canSendToEs.get()) {
            flushExecutor.submit(() -> {
                boolean sent = sendLogToElasticsearch(entry);
                if (!sent) {
                    // 发送失败，缓存到队列或文件
                    cacheLogEntry(entry);
                }
            });
        } else {
            cacheLogEntry(entry);
        }
    }

    /**
     * 缓存日志条目到队列或文件
     */
    private void cacheLogEntry(LogEntry entry) {
        if (logQueue == null || !logQueue.offer(entry)) {
            droppedLogs.incrementAndGet();
            writeLogToFile(entry);
        } else {
            bufferedLogs.incrementAndGet();
        }
    }

    /**
     * 将日志写入本地文件
     */
    private void writeLogToFile(LogEntry entry) {
        if (bufferFilePath == null) return;

        try {
            String jsonLine = objectMapper.writeValueAsString(entry.toJson()) + "\n";

            long fileSize = Files.exists(bufferFilePath) ? Files.size(bufferFilePath) : 0;
            long maxSize = maxFileSizeMb * 1024L * 1024L;

            if (fileSize > maxSize) {
                rotateBufferFile();
            }

            Files.writeString(
                    bufferFilePath,
                    jsonLine,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.error("写入日志到缓冲区文件失败", e);
        }
    }

    /**
     * 轮转缓冲区文件
     */
    private void rotateBufferFile() {
        try {
            Path rotatedPath = bufferFilePath.getParent().resolve(
                    "buffered-logs-" + System.currentTimeMillis() + ".jsonl"
            );
            Files.move(bufferFilePath, rotatedPath);
            log.infof("日志缓冲区文件已轮转: %s", rotatedPath.getFileName());
        } catch (IOException e) {
            log.error("轮转日志缓冲区文件失败", e);
        }
    }

    /**
     * 加载已存在的缓冲日志
     */
    private void loadExistingBufferedLogs() {
        if (bufferFilePath == null || !Files.exists(bufferFilePath)) return;

        try (BufferedReader reader = Files.newBufferedReader(bufferFilePath, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                try {
                    LogEntry entry = LogEntry.fromJson(objectMapper.readTree(line));
                    if (logQueue != null && logQueue.offer(entry)) {
                        count++;
                    } else {
                        droppedLogs.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.debugf("解析缓冲日志行失败: %s", line);
                }
            }

            Files.deleteIfExists(bufferFilePath);
            if (count > 0) {
                bufferedLogs.addAndGet(count);
                log.infof("已加载 %d 条缓冲日志", count);
            }
        } catch (IOException e) {
            log.error("加载缓冲日志失败", e);
        }
    }

    /**
     * 尝试刷新缓冲的日志到 ElasticSearch
     */
    private void tryFlushBufferedLogs() {
        // 检查连接是否就绪
        if (!connectionManager.isAvailable() || !canSendToEs.get()) {
            if (canSendToEs.get()) {
                // 如果之前已就绪，现在不可用了，重置状态
                canSendToEs.set(false);
                log.warn("ElasticSearch 不可用，日志将缓存到文件");
            }
            return;
        }

        if (logQueue == null || logQueue.isEmpty()) {
            return;
        }

        List<LogEntry> batch = new ArrayList<>();
        logQueue.drainTo(batch, 100);

        if (batch.isEmpty()) return;

        try {
            sendBatchToElasticsearch(batch);
            long remaining = bufferedLogs.addAndGet(-batch.size());
            sentLogs.addAndGet(batch.size());

            if (!logQueue.isEmpty()) {
                scheduledExecutor.schedule(this::tryFlushBufferedLogs, 1, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // 发送失败，将日志放回队列
            batch.forEach(logQueue::offer);
            log.debugf("批量发送日志失败，已回退到队列: %s", e.getMessage());
        }
    }

    /**
     * 发送单条日志到 ElasticSearch
     *
     * @return 是否发送成功
     */
    private boolean sendLogToElasticsearch(LogEntry entry) {
        try {
            String documentJson = objectMapper.writeValueAsString(entry.toJson());

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/" + WRITE_ALIAS + "/_doc"))
                    .POST(HttpRequest.BodyPublishers.ofString(documentJson))
                    .header("Content-Type", "application/json")
                    .build();

            var response = connectionManager.sendRequest(request);

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                sentLogs.incrementAndGet();
                return true;
            } else {
                log.debugf("发送日志到 ElasticSearch 失败: %s", response.body());
                return false;
            }
        } catch (Exception e) {
            log.debugf("发送日志到 ElasticSearch 失败: %s", e.getMessage());
            return false;
        }
    }

    /**
     * 批量发送日志到 ElasticSearch
     */
    private void sendBatchToElasticsearch(List<LogEntry> entries) throws Exception {
        StringBuilder bulkBody = new StringBuilder();

        for (LogEntry entry : entries) {
            bulkBody.append("{\"index\":{}}\n");
            bulkBody.append(objectMapper.writeValueAsString(entry.toJson()));
            bulkBody.append("\n");
        }

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/" + WRITE_ALIAS + "/_bulk"))
                .POST(HttpRequest.BodyPublishers.ofString(bulkBody.toString()))
                .header("Content-Type", "application/x-ndjson")
                .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() != 200) {
            throw new IOException("Bulk insert failed: " + response.body());
        }
    }

    /**
     * 刷新剩余日志到文件
     */
    private void flushRemainingLogs() {
        if (logQueue == null) return;

        List<LogEntry> remaining = new ArrayList<>();
        logQueue.drainTo(remaining);

        for (LogEntry entry : remaining) {
            writeLogToFile(entry);
        }
    }

    public long getBufferedCount() {
        return bufferedLogs.get();
    }

    public long getDroppedCount() {
        return droppedLogs.get();
    }

    public long getSentCount() {
        return sentLogs.get();
    }

    public int getQueueSize() {
        return logQueue != null ? logQueue.size() : 0;
    }

    public boolean isReady() {
        return canSendToEs.get();
    }

    /**
     * 日志条目
     */
    public record LogEntry(
            OffsetDateTime timestamp,
            String level,
            String message,
            String loggerName,
            String threadName,
            String exception
    ) {
        public static LogEntry fromJson(com.fasterxml.jackson.databind.JsonNode node) {
            return new LogEntry(
                    OffsetDateTime.parse(node.path("@timestamp").asText()),
                    node.path("level").asText("INFO"),
                    node.path("message").asText(""),
                    node.path("logger").asText(""),
                    node.path("thread").asText(""),
                    node.path("exception").asText(null)
            );
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("@timestamp", timestamp.format(DATE_FORMATTER));
            node.put("level", level);
            node.put("message", message);
            node.put("logger", loggerName);
            node.put("thread", threadName);
            if (exception != null) {
                node.put("exception", exception);
            }
            return node;
        }
    }
}
