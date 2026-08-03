package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scans uploaded files through ClamAV's bounded INSTREAM protocol.
 * The application never treats an unavailable scanner as a clean result.
 */
@ApplicationScoped
public class MediaVirusScanService {

    private static final int BUFFER_SIZE = 64 * 1024;

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

    public ScanResult scan(Path file) {
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
            int timeoutMillis = Math.toIntExact(Math.max(1000L, timeout.toMillis()));
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
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
        return enabled;
    }

    public boolean isRequired() {
        return required;
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
}
