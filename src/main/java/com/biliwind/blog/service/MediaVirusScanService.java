package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Scans uploaded files through ClamAV's bounded INSTREAM protocol.
 * The application never treats an unavailable scanner as a clean result.
 */
@ApplicationScoped
public class MediaVirusScanService {

    private static final Logger log = Logger.getLogger(MediaVirusScanService.class);
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long AUTO_DETECTION_RETRY_MILLIS = 15_000L;

    @ConfigProperty(name = "windblog.media.virus-scan.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "windblog.media.virus-scan.required", defaultValue = "false")
    boolean required;

    @ConfigProperty(name = "windblog.media.virus-scan.host", defaultValue = "127.0.0.1")
    String host;

    @ConfigProperty(name = "windblog.media.virus-scan.port", defaultValue = "3310")
    int port;

    @ConfigProperty(name = "windblog.media.virus-scan.timeout", defaultValue = "30S")
    java.time.Duration timeout;

    @ConfigProperty(name = "windblog.media.virus-scan.auto-detect", defaultValue = "true")
    boolean autoDetect;

    @ConfigProperty(
            name = "windblog.media.virus-scan.auto-detect-hosts",
            defaultValue = "clamav,127.0.0.1,host.docker.internal"
    )
    String autoDetectHosts;

    private volatile String detectedHost;
    private volatile boolean detectedAutomatically;
    private volatile long lastAutoDetectionAt;

    public ScanResult scan(Path file) {
        ensureAutoDetected();
        if (!enabled) {
            if (required) {
                return new ScanResult(Status.UNAVAILABLE, "病毒扫描已要求启用，但扫描服务未启用");
            }
            return new ScanResult(Status.DISABLED, "病毒扫描未启用");
        }
        if (file == null || !Files.isRegularFile(file)) {
            return new ScanResult(Status.UNAVAILABLE, "待扫描文件不存在");
        }

        try (Socket socket = new Socket()) {
            int timeoutMillis = scanTimeoutMillis();
            socket.connect(new InetSocketAddress(currentHost(), port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            try (InputStream input = Files.newInputStream(file)) {
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                }
                output.writeInt(0);
                output.flush();
            }
            return parseResponse(socket.getInputStream());
        } catch (Exception exception) {
            return new ScanResult(Status.UNAVAILABLE, "病毒扫描服务不可用");
        }
    }

    private ScanResult parseResponse(InputStream responseStream) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseStream, StandardCharsets.US_ASCII));
        String response = reader.readLine();
        if (response == null || response.isBlank()) {
            return new ScanResult(Status.UNAVAILABLE, "病毒扫描服务未返回结果");
        }
        if (response.endsWith("OK")) {
            return new ScanResult(Status.CLEAN, "clean");
        }
        if (response.endsWith("FOUND")) {
            return new ScanResult(Status.INFECTED, "病毒扫描拒绝文件");
        }
        return new ScanResult(Status.UNAVAILABLE, "病毒扫描服务返回异常结果");
    }

    public boolean isEnabled() {
        ensureAutoDetected();
        return enabled;
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * Performs a bounded ClamAV PING without uploading or persisting a sample file.
     * This is intentionally separate from media scanning so the admin page can
     * verify the service without creating an audit or media record.
     */
    public ProbeResult probe() {
        ensureAutoDetected();
        if (!enabled) {
            if (autoDetect) {
                return new ProbeResult(ProbeStatus.DISABLED, false, "病毒扫描未启用，且未自动发现可用的 ClamAV");
            }
            return new ProbeResult(ProbeStatus.DISABLED, false, "病毒扫描未启用");
        }

        try (Socket socket = new Socket()) {
            int timeoutMillis = probeTimeoutMillis();
            socket.connect(new InetSocketAddress(currentHost(), port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String response = readProbeResponse(socket.getInputStream());
            if (response.contains("PONG")) {
                return new ProbeResult(ProbeStatus.AVAILABLE, true, "ClamAV 已响应 PONG");
            }
            return new ProbeResult(ProbeStatus.UNAVAILABLE, false, "ClamAV 返回了无法识别的响应");
        } catch (Exception exception) {
            return new ProbeResult(ProbeStatus.UNAVAILABLE, false, "ClamAV 连接失败");
        }
    }

    public Configuration getConfiguration() {
        ensureAutoDetected();
        return new Configuration(enabled, required, currentHost(), port, timeout, detectedAutomatically);
    }

    private void ensureAutoDetected() {
        if (!autoDetect || detectedAutomatically) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (this) {
            if (!autoDetect || detectedAutomatically) {
                return;
            }
            if (now - lastAutoDetectionAt < AUTO_DETECTION_RETRY_MILLIS) {
                return;
            }
            lastAutoDetectionAt = now;

            String configuredHost = currentHost();
            if (enabled && probeHost(configuredHost)) {
                return;
            }

            for (String candidate : detectionCandidates()) {
                if (enabled && candidate.equals(configuredHost)) {
                    continue;
                }
                if (probeHost(candidate)) {
                    detectedHost = candidate;
                    detectedAutomatically = true;
                    enabled = true;
                    log.infof("自动发现 ClamAV，使用 %s:%d", candidate, port);
                    return;
                }
            }
        }
    }

    private Set<String> detectionCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, host);
        if (autoDetectHosts != null) {
            String[] configuredCandidates = autoDetectHosts.split(",");
            for (String candidate : configuredCandidates) {
                addCandidate(candidates, candidate);
            }
        }
        return candidates;
    }

    private void addCandidate(Set<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            candidates.add(candidate.trim());
        }
    }

    private boolean probeHost(String candidate) {
        try (Socket socket = new Socket()) {
            int timeoutMillis = probeTimeoutMillis();
            socket.connect(new InetSocketAddress(candidate, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readProbeResponse(socket.getInputStream()).contains("PONG");
        } catch (Exception exception) {
            return false;
        }
    }

    private String currentHost() {
        if (detectedHost != null && !detectedHost.isBlank()) {
            return detectedHost;
        }
        return host;
    }

    private int scanTimeoutMillis() {
        long configuredMillis = timeout == null ? 30_000L : timeout.toMillis();
        return Math.toIntExact(Math.max(1000L, Math.min(configuredMillis, Integer.MAX_VALUE)));
    }

    private int probeTimeoutMillis() {
        long configuredMillis = timeout == null ? 3000L : timeout.toMillis();
        return (int) Math.max(1000L, Math.min(configuredMillis, 3000L));
    }

    private String readProbeResponse(InputStream input) throws IOException {
        StringBuilder response = new StringBuilder();
        for (int index = 0; index < 32; index++) {
            int value = input.read();
            if (value < 0 || value == 0 || value == '\n' || value == '\r') {
                break;
            }
            response.append((char) value);
        }
        return response.toString();
    }

    public record ScanResult(Status status, String reason) {
        public boolean isClean() {
            return status == Status.CLEAN || status == Status.DISABLED;
        }
    }

    public enum Status {
        CLEAN,
        INFECTED,
        UNAVAILABLE,
        DISABLED
    }

    public enum ProbeStatus {
        AVAILABLE,
        UNAVAILABLE,
        DISABLED
    }

    public record ProbeResult(ProbeStatus status, boolean available, String message) {
    }

    public record Configuration(
            boolean enabled,
            boolean required,
            String host,
            int port,
            Duration timeout,
            boolean autoDetected
    ) {
    }
}
