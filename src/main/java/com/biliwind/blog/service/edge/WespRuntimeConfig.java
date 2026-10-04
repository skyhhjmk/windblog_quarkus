package com.biliwind.blog.service.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * Small, local-only WESP bootstrap store written by an authenticated node
 * connection. Explicit environment variables remain the operator override;
 * this file lets an installed home node continue running after the admin login
 * exchange has completed.
 * The file is mode 0600 and never exposed by an API response.
 */
@ApplicationScoped
public class WespRuntimeConfig {
    private static final Logger LOG = LoggerFactory.getLogger(WespRuntimeConfig.class);
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "windblog.wesp.config-file", defaultValue = "data/wesp-config.json")
    String configuredPath;

    private volatile Values values = Values.empty();

    @PostConstruct
    void initialize() {
        load();
    }

    public Optional<String> peerUrl() {
        return Optional.ofNullable(values.peerUrl);
    }

    public boolean isActivePoll() {
        return values.activePoll;
    }

    public Optional<String> authToken() {
        return Optional.ofNullable(values.authToken);
    }

    public Optional<String> tenantId() {
        return Optional.ofNullable(values.tenantId);
    }

    public Optional<String> datasetId() {
        return Optional.ofNullable(values.datasetId);
    }

    public Optional<String> incarnation() {
        return Optional.ofNullable(values.incarnation);
    }

    public Optional<String> nodeId() {
        return Optional.ofNullable(values.nodeId);
    }

    public Optional<String> peerNodeId() {
        return Optional.ofNullable(values.peerNodeId);
    }

    public boolean isConfigured() {
        return (values.peerUrl != null || values.activePoll) && values.authToken != null;
    }

    /** Persist only the runtime fields needed by WESP. */
    public synchronized void apply(JsonNode payload) {
        String nodeId = text(payload, "node_id");
        String peerUrl = text(payload, "peer_url");
        String authToken = text(payload, "auth_token");
        String tenantId = text(payload, "tenant_id");
        String datasetId = text(payload, "dataset_id");
        String incarnation = text(payload, "incarnation");
        String peerNodeId = text(payload, "peer_node_id");
        boolean activePoll = payload.path("active_poll").asBoolean(false);
        if (nodeId == null || peerUrl == null && !activePoll || authToken == null || tenantId == null
                || datasetId == null || incarnation == null
                || (peerUrl != null && !peerUrl.startsWith("http://") && !peerUrl.startsWith("https://"))
                || authToken.length() < 32) {
            throw new IllegalArgumentException("WESP 运行配置缺少有效字段");
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("node_id", nodeId);
        root.put("peer_url", peerUrl == null ? "" : peerUrl.replaceAll("/+$", ""));
        root.put("active_poll", activePoll);
        root.put("auth_token", authToken);
        root.put("tenant_id", tenantId);
        root.put("dataset_id", datasetId);
        root.put("incarnation", incarnation);
        if (peerNodeId != null) root.put("peer_node_id", peerNodeId);
        root.put("version", 1);
        Path target = configPath();
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent == null ? Path.of(".") : parent,
                    ".wesp-config-", ".part");
            try {
                setOwnerOnly(temporary);
                Files.writeString(temporary, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                setOwnerOnly(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            values = new Values(nodeId, text(root, "peer_url"), authToken,
                    tenantId, datasetId, incarnation, activePoll, peerNodeId);
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存 WESP 本地运行配置", exception);
        }
    }

    /** Apply an authenticated bootstrap without accepting a transport code. */
    public synchronized void applyBootstrap(String nodeId, String peerUrl, String authToken,
                                            String tenantId, String datasetId, String incarnation) {
        applyBootstrap(nodeId, peerUrl, authToken, tenantId, datasetId, incarnation, false);
    }

    public synchronized void applyBootstrap(String nodeId, String peerUrl, String authToken,
                                            String tenantId, String datasetId, String incarnation,
                                            boolean activePoll) {
        applyBootstrap(nodeId, peerUrl, authToken, tenantId, datasetId, incarnation, activePoll, null);
    }

    public synchronized void applyBootstrap(String nodeId, String peerUrl, String authToken,
                                            String tenantId, String datasetId, String incarnation,
                                            boolean activePoll, String peerNodeId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("node_id", nodeId == null ? "" : nodeId);
        payload.put("peer_url", peerUrl == null ? "" : peerUrl);
        payload.put("auth_token", authToken == null ? "" : authToken);
        payload.put("tenant_id", tenantId == null ? "" : tenantId);
        payload.put("dataset_id", datasetId == null ? "" : datasetId);
        payload.put("incarnation", incarnation == null ? "" : incarnation);
        payload.put("active_poll", activePoll);
        if (peerNodeId != null) payload.put("peer_node_id", peerNodeId);
        apply(payload);
    }

    private void load() {
        Path target = configPath();
        try {
            if (!Files.isRegularFile(target)) return;
            JsonNode root = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
            String nodeId = text(root, "node_id");
            String peerUrl = text(root, "peer_url");
            String authToken = text(root, "auth_token");
            String tenantId = text(root, "tenant_id");
            String datasetId = text(root, "dataset_id");
            String incarnation = text(root, "incarnation");
            String peerNodeId = text(root, "peer_node_id");
            boolean activePoll = root.path("active_poll").asBoolean(false);
            if (nodeId != null && (peerUrl != null || activePoll) && authToken != null && authToken.length() >= 32
                    && tenantId != null && datasetId != null && incarnation != null) {
                values = new Values(nodeId, peerUrl, authToken, tenantId, datasetId,
                        incarnation, activePoll, peerNodeId);
            } else {
                LOG.warn("WESP 本地运行配置字段不完整，忽略 {}", target);
            }
        } catch (Exception exception) {
            LOG.warn("WESP 本地运行配置读取失败，继续使用环境变量: {}", target);
        }
    }

    private Path configPath() {
        String path = configuredPath == null ? "" : configuredPath.trim();
        return Path.of(path.isBlank() ? "data/wesp-config.json" : path);
    }

    private static void setOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows/non-POSIX filesystems still rely on the private data dir.
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private record Values(String nodeId, String peerUrl, String authToken,
                          String tenantId, String datasetId, String incarnation, boolean activePoll,
                          String peerNodeId) {
        private static Values empty() {
            return new Values(null, null, null, null, null, null, false, null);
        }
    }
}
