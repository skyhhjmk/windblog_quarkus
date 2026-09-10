package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.model.WespSyncCursor;
import com.biliwind.blog.model.WespSyncOperation;
import com.biliwind.blog.model.WespSyncReceipt;
import com.biliwind.blog.model.WespSyncManifest;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.OutputStream;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * WESP v1 application-layer replication.  This replaces the old primary-to-edge
 * gRPC data push when enabled.  A node always initiates the HTTPS request; the
 * receiver only stores and applies durable operations.
 */
@jakarta.enterprise.context.ApplicationScoped
public class WespSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(WespSyncService.class);
    private static final int MAX_BATCH_OPERATIONS = 256;
    private static final int MAX_BATCH_BYTES = 1_048_576;
    private static final int MAX_OPERATION_BYTES = 65_536;
    private static final int MAX_BLOCK_BYTES = 2_097_152;

    @Inject ObjectMapper mapper;
    @Inject EntityManager entityManager;
    @Inject NodeRoleService nodeRoleService;
    @Inject EdgeSyncDataApplyService syncDataApplyService;
    @Inject EdgeReadOnlyState readOnlyState;
    @Inject StorageService storageService;
    @Inject WespRuntimeConfig runtimeConfig;

    @ConfigProperty(name = "windblog.wesp.enabled", defaultValue = "true") boolean enabled;
    @ConfigProperty(name = "windblog.wesp.peer-url") Optional<String> configuredPeerUrl;
    @ConfigProperty(name = "windblog.wesp.auth-token") Optional<String> authToken;
    @ConfigProperty(name = "windblog.wesp.tenant-id", defaultValue = "default") String tenantId;
    @ConfigProperty(name = "windblog.wesp.dataset-id", defaultValue = "public") String datasetId;
    @ConfigProperty(name = "windblog.wesp.incarnation") Optional<String> configuredIncarnation;
    @ConfigProperty(name = "windblog.wesp.block-store", defaultValue = "data/wesp-blocks") String blockStore;
    @ConfigProperty(name = "media.upload.dir", defaultValue = "uploads") String mediaUploadDir;
    @ConfigProperty(name = "windblog.wesp.request-timeout", defaultValue = "30S") Duration requestTimeout;
    @ConfigProperty(name = "windblog.wesp.max-pull-items", defaultValue = "256") int maxPullItems;
    @ConfigProperty(name = "windblog.wesp.max-egress-bytes-per-minute", defaultValue = "0") long maxEgressBytesPerMinute;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private String incarnation;
    private volatile boolean sessionNegotiated;
    private volatile long lastSessionMillis;
    private long budgetWindowStartMillis;
    private long budgetUsed;

    @PostConstruct
    void initialize() {
        String configuredIncarnationValue = runtimeConfig.incarnation()
                .orElse(configuredIncarnation.orElse(""));
        incarnation = configuredIncarnationValue.isBlank()
                ? UUID.randomUUID().toString()
                : configuredIncarnationValue.trim();
        if (isEnabled()) {
            try {
                Files.createDirectories(Path.of(blockStore));
            } catch (IOException exception) {
                LOG.warn("无法创建 WESP 附件块目录，附件同步将在请求时失败: {}", blockStore);
            }
        }
    }

    /**
     * Apply a runtime bootstrap written after application startup. The next
     * scheduler cycle must negotiate with the bootstrapped incarnation instead of
     * reusing the pre-bootstrap session identity.
     */
    public synchronized void refreshRuntimeConfig() {
        String configuredIncarnationValue = runtimeConfig.incarnation()
                .orElse(configuredIncarnation.orElse(""));
        String next = configuredIncarnationValue.isBlank()
                ? incarnation
                : configuredIncarnationValue.trim();
        if (next != null && !next.equals(incarnation)) {
            incarnation = next;
            sessionNegotiated = false;
            lastSessionMillis = 0;
        }
    }

    public boolean isEnabled() {
        return enabled || runtimeConfig.isConfigured();
    }

    public boolean hasPeer() {
        return normalizePeerUrl() != null;
    }

    /**
     * Forward a write request over the authenticated outbound WESP connection.
     * The edge node never accepts a socket from the primary; it creates a
     * short request/response exchange on the same HTTPS peer URL.
     */
    public RoutedHttpExchange.Response forwardWriteRequest(RoutedHttpExchange.Request request) {
        if (request == null || request.method() == null || request.path() == null) {
            return unavailableResponse("WESP 回源请求参数无效");
        }
        byte[] body = request.body() == null ? new byte[0] : request.body();
        if (body.length > 10_485_760) {
            return new RoutedHttpExchange.Response(413,
                    Map.of("Content-Type", "application/json"),
                    "{\"success\":false,\"message\":\"回源请求体超过限制\"}"
                            .getBytes(StandardCharsets.UTF_8), "routed request body too large");
        }
        try {
            negotiateSession();
            if (nodeRoleService.isEdgeNode()) readOnlyState.markPrimaryOnline();
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("method", request.method());
            envelope.put("path", request.path());
            envelope.put("query", request.query() == null ? "" : request.query());
            ObjectNode headers = envelope.putObject("headers");
            if (request.headers() != null) request.headers().forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) headers.put(key, value);
            });
            envelope.put("body", java.util.Base64.getEncoder().encodeToString(body));
            HttpResponse<String> response = send("POST", "/sync/v1/requests", mapper.writeValueAsString(envelope),
                    "application/json");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("WESP routed request HTTP " + response.statusCode());
            }
            JsonNode result = parseObject(response.body());
            int status = result.path("status").asInt(502);
            String encodedBody = text(result, "body");
            byte[] responseBody = encodedBody == null || encodedBody.isBlank()
                    ? new byte[0] : java.util.Base64.getDecoder().decode(encodedBody);
            Map<String, String> responseHeaders = new java.util.HashMap<>();
            JsonNode responseHeadersNode = result.get("headers");
            if (responseHeadersNode != null && responseHeadersNode.isObject()) {
                responseHeadersNode.fields().forEachRemaining(entry -> responseHeaders.put(entry.getKey(), entry.getValue().asText()));
            }
            String errorMessage = text(result, "errorMessage");
            return new RoutedHttpExchange.Response(status, responseHeaders, responseBody,
                    errorMessage == null ? "" : errorMessage);
        } catch (Exception exception) {
            if (nodeRoleService.isEdgeNode()) {
                readOnlyState.markPrimaryOffline("WESP 主节点不可用，当前边缘节点只读");
            }
            LOG.debug("WESP 回源写请求失败: {}", safeError(exception.getMessage()));
            return unavailableResponse("WESP 主节点不可用，当前边缘节点只读");
        }
    }

    private RoutedHttpExchange.Response unavailableResponse(String message) {
        byte[] body = ("{\"success\":false,\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return new RoutedHttpExchange.Response(503, Map.of("Content-Type", "application/json"), body, message);
    }

    /** Bearer authentication is the low-cost baseline; mTLS can be added at the proxy. */
    public boolean isAuthorized(String authorization, String remoteNodeId, String body) {
        String expectedToken = effectiveAuthToken();
        if (expectedToken.isBlank() || authorization == null) return false;
        String prefix = "Bearer ";
        if (!authorization.startsWith(prefix)) return false;
        String presented = authorization.substring(prefix.length()).trim();
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    public String nodeId() {
        return nodeRoleService.getNodeId();
    }

    /**
     * Returns the current primary's WESP token for an authenticated node
     * bootstrap. The token is only placed in the target's private runtime
     * configuration and is never included in the HTTP response.
     */
    public String bootstrapAuthToken() {
        return effectiveAuthToken();
    }

    public String bootstrapTenantId() {
        return effectiveTenantId();
    }

    public String bootstrapDatasetId() {
        return effectiveDatasetId();
    }

    /** Record a successful inbound session for the admin-side WESP node view. */
    @Transactional
    public void recordPeerSession(String remoteNodeId) {
        EdgeNode peer = EdgeNode.findByNodeId(remoteNodeId);
        if (peer == null || !Boolean.TRUE.equals(peer.isEnabled)) return;
        peer.status = "ONLINE";
        peer.lastHeartbeat = now();
        if (peer.metrics == null) peer.metrics = new java.util.HashMap<>();
        peer.metrics.put("protocol", "WESP/1");
        peer.metrics.put("lastSession", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    /**
     * Ask one registered WESP peer to replay the complete public operation log.
     * The request itself is an outbox operation, so a public node never opens
     * an inbound connection to the home node.
     */
    @Transactional
    public String triggerFullSync(String targetNodeId, boolean force) {
        EdgeNode target = EdgeNode.findByNodeId(targetNodeId);
        if (target == null || target.connectionType != com.biliwind.blog.model.EdgeConnectionType.WESP) {
            throw new IllegalArgumentException("WESP 节点不存在");
        }
        String peerUrl = target.apiUrl == null ? "" : target.apiUrl.trim().replaceAll("/+$", "");
        if (!peerUrl.startsWith("http://") && !peerUrl.startsWith("https://")) {
            throw new IllegalArgumentException("WESP 节点缺少主节点连接域名");
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("target_node_id", targetNodeId);
        request.put("mode", "FULL");
        request.put("force", force);
        request.put("peer_id", sha256(peerUrl));
        request.put("requested_at", now().toString());
        return enqueueLocalOperation("SYNC_REQUEST", "UPSERT", targetNodeId, request.toString());
    }

    public void validateSessionRequest(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BATCH_BYTES) {
            throw new WespProtocolException(Response.Status.REQUEST_ENTITY_TOO_LARGE, "SESSION_TOO_LARGE", false);
        }
        JsonNode request = parseObject(body);
        if (request.path("protocol_major").asInt(0) != 1
                || request.path("protocol_minor").asInt(-1) < 0
                || request.path("schema_version").asInt(0) < 1
                || text(request, "node_id") == null || text(request, "tenant_id") == null
                || !effectiveTenantId().equals(text(request, "tenant_id"))
                || text(request, "incarnation") == null
                || !request.path("capabilities").isArray() || !request.path("datasets").isArray()
                || !request.path("limits").isObject()) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_SESSION", false);
        }
    }

    /** Persist a local change before attempting network delivery. */
    @Transactional
    public String enqueueLocalOperation(String entityType, String action, String entityId, String payload) {
        requireText(entityType, 64, "entityType");
        requireText(action, 32, "action");
        requireText(entityId, 128, "entityId");
        String safePayload = normalizePayload(payload, "CLUSTER_PUBLIC_KEY".equals(entityType));
        if (safePayload.getBytes(StandardCharsets.UTF_8).length > MAX_OPERATION_BYTES) {
            throw new IllegalArgumentException("WESP 操作载荷超过 64 KiB，请改用附件清单");
        }
        long seq = nextSequence();
        WespSyncOperation operation = new WespSyncOperation();
        operation.opId = UUID.randomUUID().toString();
        operation.nodeId = nodeId();
        operation.datasetId = effectiveDatasetId();
        operation.incarnation = incarnation;
        operation.seq = seq;
        operation.entityType = entityType;
        operation.action = action;
        operation.entityId = entityId;
        operation.payload = safePayload;
        operation.payloadHash = sha256(safePayload);
        operation.status = "APPLIED";
        operation.createdAt = now();
        operation.appliedAt = operation.createdAt;
        operation.updatedAt = operation.createdAt;
        operation.persist();
        return operation.opId;
    }

    /** Persist a validated attachment manifest so a receiver can resume block hydration. */
    @Transactional
    public Map<String, Object> saveManifest(String body) {
        String payload = normalizePayload(body, false);
        if (!saveManifestInTransaction(payload)) {
            throw new WespProtocolException(Response.Status.CONFLICT, "MANIFEST_CONFLICT", false);
        }
        JsonNode manifest = parseObject(payload);
        return Map.of("manifest_id", text(manifest, "manifest_id"), "status", "STORED");
    }

    @Transactional
    public Optional<String> findManifestPayload(String mediaId, String variant) {
        WespSyncManifest manifest = WespSyncManifest.find(
                "mediaId = ?1 and variant = ?2", mediaId, variant).firstResult();
        return manifest == null ? Optional.empty() : Optional.of(manifest.payload);
    }

    @Transactional
    public boolean hasLocalOperation(String entityType, String entityId, String payload) {
        String hash = sha256(normalizePayload(payload, false));
        return WespSyncOperation.count("nodeId = ?1 and entityType = ?2 and entityId = ?3 and payloadHash = ?4",
                nodeId(), entityType, entityId, hash) > 0;
    }

    /** Compute the content identifier over a manifest with manifest_id omitted. */
    public String computeManifestId(String body) {
        JsonNode parsed = parseObject(normalizePayload(body, false));
        ObjectNode unsigned = (ObjectNode) parsed.deepCopy();
        unsigned.remove("manifest_id");
        try {
            return sha256(canonicalBytes(unsigned));
        } catch (IOException exception) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_MANIFEST", false);
        }
    }

    /** Re-attempt block publication for an already durable manifest. */
    public void republishManifestBlocks(String body) {
        String payload = normalizePayload(body, false);
        JsonNode manifest = parseObject(payload);
        JsonNode blocks = manifest.get("blocks");
        if (blocks == null || !blocks.isArray()) return;
        for (JsonNode item : blocks) {
            String hash = text(item, "hash");
            if (hash == null) continue;
            byte[] bytes = getBlock(hash);
            if (bytes != null) pushBlock(hash, bytes);
        }
    }

    private boolean saveManifestInTransaction(String payload) {
        JsonNode manifest = parseObject(payload);
        validateManifest(manifest);
        String manifestId = text(manifest, "manifest_id");
        String mediaId = text(manifest, "media_id");
        String variant = text(manifest, "variant");
        WespSyncManifest existing = WespSyncManifest.findById(manifestId);
        if (existing != null) return existing.payload.equals(payload);
        WespSyncManifest sameMedia = WespSyncManifest.find(
                "mediaId = ?1 and variant = ?2", mediaId, variant).firstResult();
        if (sameMedia != null && !sameMedia.manifestId.equals(manifestId)) return false;
        WespSyncManifest row = new WespSyncManifest();
        row.manifestId = manifestId;
        row.mediaId = mediaId;
        row.variant = variant;
        row.fileHash = text(manifest, "file_hash");
        row.fileSize = manifest.path("file_size").asLong();
        row.payload = payload;
        row.createdAt = now();
        row.updatedAt = row.createdAt;
        row.persist();
        return true;
    }

    private void validateManifest(JsonNode manifest) {
        String manifestId = text(manifest, "manifest_id");
        String mediaId = text(manifest, "media_id");
        String variant = text(manifest, "variant");
        String fileHash = text(manifest, "file_hash");
        long fileSize = manifest.path("file_size").asLong(-1);
        JsonNode blocks = manifest.get("blocks");
        if (manifest.path("schema_version").asInt(0) < 1 || manifestId == null
                || !manifestId.matches("[0-9a-f]{64}") || mediaId == null || mediaId.length() > 128
                || variant == null || variant.length() > 32 || fileHash == null
                || !fileHash.matches("[0-9a-f]{64}") || fileSize < 0 || fileSize > (1L << 40)
                || blocks == null || !blocks.isArray() || blocks.size() > 1024) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_MANIFEST", false);
        }
        if (!manifestId.equals(computeManifestId(manifest.toString()))) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "MANIFEST_ID_MISMATCH", false);
        }
        if (fileSize == 0 && (!blocks.isEmpty() || !fileHash.equals(sha256(new byte[0])))) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "MANIFEST_SIZE_MISMATCH", false);
        }
        if (fileSize > 0 && blocks.isEmpty()) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "MANIFEST_SIZE_MISMATCH", false);
        }
        long total = 0;
        for (JsonNode block : blocks) {
            String hash = text(block, "hash");
            long size = block.path("size").asLong(-1);
            if (hash == null || !hash.matches("[0-9a-f]{64}") || size < 1 || size > MAX_BLOCK_BYTES) {
                throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_MANIFEST_BLOCK", false);
            }
            if (total > fileSize - size) {
                throw new WespProtocolException(Response.Status.BAD_REQUEST, "MANIFEST_SIZE_MISMATCH", false);
            }
            total += size;
        }
        if (total != fileSize) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "MANIFEST_SIZE_MISMATCH", false);
        }
    }

    /** Called by the receiving HTTP resource after authentication and framing checks. */
    @Transactional
    public Map<String, Object> receiveBatch(String body) {
        return receiveBatch(null, body);
    }

    @Transactional
    public Map<String, Object> receiveBatch(String expectedBatchId, String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BATCH_BYTES) {
            throw new WespProtocolException(Response.Status.REQUEST_ENTITY_TOO_LARGE, "BATCH_TOO_LARGE", true);
        }
        JsonNode root = parseObject(body);
        String batchId = text(root, "batch_id");
        JsonNode operations = root.get("operations");
        if (batchId == null || !isUuid(batchId) || operations == null || !operations.isArray()
                || operations.size() > MAX_BATCH_OPERATIONS) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_BATCH", false);
        }
        if (expectedBatchId != null && !expectedBatchId.equals(batchId)) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "BATCH_ID_MISMATCH", false);
        }
        String bodyHash = sha256(normalizePayload(body, false));
        List<?> prior = entityManager.createNativeQuery(
                "select body_hash from wesp_sync_batches where batch_id = :batch")
                .setParameter("batch", batchId).getResultList();
        if (!prior.isEmpty()) {
            String priorHash = String.valueOf(prior.get(0));
            if (!priorHash.equals(bodyHash)) {
                throw new WespProtocolException(Response.Status.CONFLICT, "BATCH_ID_CONFLICT", false);
            }
            return Map.of("batch_id", batchId, "results", List.of(), "status", "DUPLICATE", "duplicate", true);
        }
        entityManager.createNativeQuery(
                "insert into wesp_sync_batches(batch_id, body_hash) values (:batch, :hash)")
                .setParameter("batch", batchId).setParameter("hash", bodyHash).executeUpdate();

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode wrapped : operations) {
            JsonNode signed = wrapped != null && wrapped.isObject() && wrapped.has("signed")
                    ? wrapped.get("signed") : wrapped;
            String signature = wrapped != null && wrapped.isObject() ? text(wrapped, "signature") : null;
            Map<String, Object> result = receiveOperation(signed, signature);
            results.add(result);
            if ("MEDIA_MANIFEST".equals(text(signed, "entity_type"))
                    && List.of("APPLIED", "DUPLICATE").contains(result.get("status"))) {
                try {
                    materializeIfComplete(signed.get("payload"));
                } catch (Exception exception) {
                    LOG.debug("WESP 批次附件尚未具备完整块，等待后续块上传: {}", exception.getMessage());
                }
            }
        }
        return Map.of("batch_id", batchId, "results", results, "status", "RECEIVED", "duplicate", false);
    }

    private Map<String, Object> receiveOperation(JsonNode signed) {
        return receiveOperation(signed, null);
    }

    private Map<String, Object> receiveOperation(JsonNode signed, String providedSignature) {
        if (signed == null || !signed.isObject()) {
            return result(null, "REJECTED", "INVALID_OPERATION");
        }
        String opId = text(signed, "op_id");
        String nodeId = text(signed, "node_id");
        String tenant = text(signed, "tenant_id");
        String dataset = text(signed, "dataset_id");
        String incarnationValue = text(signed, "incarnation");
        String seqText = text(signed, "seq");
        String entityType = text(signed, "entity_type");
        String action = text(signed, "operation");
        String entityId = text(signed, "entity_id");
        JsonNode payloadNode = signed.get("payload");
        String payloadHash = text(signed, "payload_hash");
        if (signed.path("protocol_major").asInt(0) != 1 || signed.path("schema_version").asInt(0) < 1) {
            return result(opId, "REJECTED", "UNSUPPORTED_PROTOCOL");
        }
        if (opId == null || opId.length() > 200 || nodeId == null || nodeId.length() > 100
                || tenant == null || tenant.length() > 100
                || dataset == null || dataset.length() > 100 || incarnationValue == null
                || seqText == null || entityType == null || action == null || entityId == null
                || payloadNode == null || payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
            return result(opId, "REJECTED", "INVALID_OPERATION");
        }
        if (!effectiveTenantId().equals(tenant)) {
            return result(opId, "REJECTED", "TENANT_NOT_ALLOWED");
        }
        if (!List.of("POST", "TAG", "CATEGORY", "MEDIA", "MEDIA_MANIFEST", "CLUSTER_PUBLIC_KEY", "SYNC_REQUEST").contains(entityType)
                || !List.of("UPSERT", "UPDATE", "FORCE_UPSERT", "DELETE").contains(action)) {
            return result(opId, "REJECTED", "UNSUPPORTED_OPERATION");
        }
        String signature = providedSignature;
        if (signature == null || !verifyOperationSignature(signed, signature)) {
            return result(opId, "REJECTED", "SIGNATURE_INVALID");
        }
        long seq;
        try {
            seq = Long.parseLong(seqText);
            if (seq <= 0) return result(opId, "REJECTED", "INVALID_SEQUENCE");
        } catch (NumberFormatException exception) {
            return result(opId, "REJECTED", "INVALID_SEQUENCE");
        }
        String payload = normalizePayload(payloadNode.toString(), false);
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_OPERATION_BYTES
                || !sha256(payload).equals(payloadHash)) {
            return result(opId, "REJECTED", "HASH_MISMATCH");
        }
        WespSyncOperation existing = WespSyncOperation.find("opId", opId).firstResult();
        if (existing != null) {
            return existing.payloadHash.equals(payloadHash)
                    ? result(opId, "DUPLICATE", null)
                    : result(opId, "REJECTED", "OP_ID_CONFLICT");
        }

        WespSyncOperation operation = new WespSyncOperation();
        operation.opId = opId;
        operation.nodeId = nodeId;
        operation.datasetId = dataset;
        operation.incarnation = incarnationValue;
        operation.seq = seq;
        operation.entityType = entityType;
        operation.action = action;
        operation.entityId = entityId;
        operation.payload = payload;
        operation.payloadHash = payloadHash;
        operation.status = "RECEIVED";
        operation.createdAt = now();
        operation.updatedAt = operation.createdAt;
        operation.persist();

        if (!"FORCE_UPSERT".equals(action)) {
            WespSyncOperation concurrent = WespSyncOperation.find(
                    "datasetId = ?1 and entityType = ?2 and entityId = ?3 and seq = ?4 and status = 'APPLIED' and nodeId <> ?5 order by rowId desc",
                    dataset, entityType, entityId, seq, nodeId).firstResult();
            if (concurrent != null && !concurrent.payloadHash.equals(payloadHash)) {
                operation.status = "CONFLICT";
                operation.lastError = "concurrent revision requires explicit merge";
                operation.updatedAt = now();
                return result(opId, "CONFLICT", "CONCURRENT_REVISION");
            }
        }

        try {
            if ("SYNC_REQUEST".equals(operation.entityType)) {
                applyFullSyncRequest(operation);
            } else if ("MEDIA_MANIFEST".equals(operation.entityType)) {
                if (!saveManifestInTransaction(operation.payload)) {
                    operation.status = "CONFLICT";
                    operation.lastError = "media manifest revision conflict";
                    operation.updatedAt = now();
                    return result(opId, "CONFLICT", "MANIFEST_CONFLICT");
                }
            } else {
                syncDataApplyService.apply(toSyncRequest(operation));
                if ("MEDIA".equals(operation.entityType) && "UPSERT".equals(operation.action)) {
                    materializeStoredManifest(operation.entityId);
                }
            }
            operation.status = "APPLIED";
            operation.appliedAt = now();
            operation.updatedAt = operation.appliedAt;
            return result(opId, "APPLIED", null);
        } catch (WespProtocolException exception) {
            operation.status = "REJECTED";
            operation.lastError = exception.code;
            operation.updatedAt = now();
            return result(opId, "REJECTED", exception.code);
        } catch (RuntimeException exception) {
            operation.status = "RECEIVED";
            operation.lastError = safeError(exception.getMessage());
            operation.updatedAt = now();
            return result(opId, "RECEIVED", "APPLY_RETRY");
        }
    }

    @Transactional
    public Map<String, Object> getChanges(long after, int requestedLimit) {
        long safeAfter = Math.max(0, after);
        int limit = Math.max(1, Math.min(MAX_BATCH_OPERATIONS, Math.min(maxPullItems, requestedLimit)));
        List<WespSyncOperation> rows = WespSyncOperation.find("rowId > ?1 order by rowId", safeAfter)
                .range(0, limit - 1).list();
        ArrayNode items = mapper.createArrayNode();
        long next = safeAfter;
        for (WespSyncOperation row : rows) {
            items.add(operationJson(row));
            next = row.rowId;
        }
        Number frontier = (Number) entityManager.createNativeQuery(
                "select coalesce(max(row_id), 0) from wesp_sync_operations").getSingleResult();
        return Map.of("items", items, "next_cursor", Long.toString(next),
                "has_more", next < frontier.longValue(), "server_frontier", frontier.toString());
    }

    @Transactional
    public Map<String, Object> inventory(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BATCH_BYTES) {
            throw new WespProtocolException(Response.Status.REQUEST_ENTITY_TOO_LARGE, "INVENTORY_TOO_LARGE", false);
        }
        JsonNode root = parseObject(body);
        JsonNode blocks = root.get("blocks");
        if (blocks == null || !blocks.isArray() || blocks.size() > 1024) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_INVENTORY", false);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : blocks) {
            String hash = text(item, "hash");
            long size = item.path("size").asLong(-1);
            if (hash == null || !hash.matches("[0-9a-f]{64}") || size < 1 || size > MAX_BLOCK_BYTES) {
                throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_INVENTORY_ITEM", false);
            }
            Number count = (Number) entityManager.createNativeQuery(
                    "select count(*) from wesp_sync_blocks where hash = :hash and size_bytes = :size")
                    .setParameter("hash", hash).setParameter("size", size).getSingleResult();
            items.add(Map.of("hash", hash, "size", size, "present", count.longValue() == 1));
        }
        return Map.of("items", items);
    }

    @Transactional
    public Map<String, Object> recordReceipt(String receiptId, String nodeId, String body) {
        if (!isUuid(receiptId) || nodeId == null || nodeId.isBlank() || nodeId.length() > 100) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_RECEIPT_ID", false);
        }
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BATCH_BYTES) {
            throw new WespProtocolException(Response.Status.REQUEST_ENTITY_TOO_LARGE, "RECEIPT_TOO_LARGE", false);
        }
        JsonNode root = parseObject(body);
        String stage = text(root, "stage");
        if (stage == null || !List.of("ACCEPTED_LOCAL", "DURABLE_R", "APPLIED", "VISIBLE").contains(stage)) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_RECEIPT_STAGE", false);
        }
        WespSyncReceipt receipt = WespSyncReceipt.findById(receiptId);
        if (receipt == null) {
            receipt = new WespSyncReceipt();
            receipt.receiptId = receiptId;
            receipt.nodeId = nodeId;
            receipt.createdAt = now();
            receipt.persist();
        }
        receipt.stage = stage;
        receipt.payload = normalizePayload(body, false);
        receipt.updatedAt = now();
        return Map.of("receipt_id", receiptId, "stage", stage, "status", "STORED");
    }

    public void saveCursor(String peerId, long cursor) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> saveCursorInTransaction(peerId, cursor));
    }

    @Transactional
    void saveCursorInTransaction(String peerId, long cursor) {
        WespSyncCursor existing = WespSyncCursor.findById(peerId);
        if (existing == null) {
            existing = new WespSyncCursor();
            existing.peerId = peerId;
            existing.cursorRow = Math.max(0, cursor);
            existing.updatedAt = now();
            existing.persist();
        } else if (cursor > existing.cursorRow) {
            existing.cursorRow = cursor;
            existing.updatedAt = now();
        }
    }

    @Transactional
    void markFullSyncRequested(String peerId) {
        WespSyncCursor cursor = WespSyncCursor.findById(peerId);
        if (cursor == null) {
            cursor = new WespSyncCursor();
            cursor.peerId = peerId;
            cursor.cursorRow = 0;
            cursor.updatedAt = now();
            cursor.fullSyncRequested = true;
            cursor.persist();
        } else {
            cursor.fullSyncRequested = true;
            cursor.updatedAt = now();
        }
    }

    @Transactional
    boolean consumeFullSyncRequested(String peerId) {
        WespSyncCursor cursor = WespSyncCursor.findById(peerId);
        if (cursor == null || !cursor.fullSyncRequested) return false;
        cursor.fullSyncRequested = false;
        cursor.updatedAt = now();
        return true;
    }

    @Transactional
    public long cursor(String peerId) {
        WespSyncCursor cursor = WespSyncCursor.findById(peerId);
        return cursor == null ? 0 : cursor.cursorRow;
    }

    /** Store a content-addressed attachment block atomically and idempotently. */
    @Transactional
    public Map<String, Object> putBlock(String hash, byte[] bytes) {
        validateHash(hash);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BLOCK_BYTES) {
            throw new WespProtocolException(Response.Status.REQUEST_ENTITY_TOO_LARGE, "BLOCK_TOO_LARGE", true);
        }
        if (!sha256(bytes).equals(hash)) {
            throw new WespProtocolException(422, "HASH_MISMATCH", false);
        }
        Path target = Path.of(blockStore).resolve(hash.substring(0, 2)).resolve(hash);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(target.getParent(), "wesp-", ".part");
                try {
                    Files.write(temporary, bytes);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            entityManager.createNativeQuery("""
                    insert into wesp_sync_blocks(hash, size_bytes, file_path)
                    values (:hash, :size, :path)
                    on conflict (hash) do update set last_seen_at = current_timestamp
                    """).setParameter("hash", hash).setParameter("size", (long) bytes.length)
                    .setParameter("path", target.toString()).executeUpdate();
            return Map.of("hash", hash, "size", bytes.length, "status", "STORED");
        } catch (IOException exception) {
            throw new WespProtocolException(507, "BLOCK_STORE_FAILED", true);
        }
    }

    public byte[] getBlock(String hash) {
        validateHash(hash);
        Path target = Path.of(blockStore).resolve(hash.substring(0, 2)).resolve(hash);
        try {
            if (!Files.exists(target)) return null;
            byte[] data = Files.readAllBytes(target);
            if (!sha256(data).equals(hash)) {
                LOG.error("WESP 附件块摘要不匹配，拒绝提供: {}", hash);
                return null;
            }
            return data;
        } catch (IOException exception) {
            return null;
        }
    }

    /** Upload one already validated block to the configured peer. */
    public boolean pushBlock(String hash, byte[] bytes) {
        if (!isEnabled() || !hasPeer()) return false;
        validateHash(hash);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BLOCK_BYTES || !sha256(bytes).equals(hash)) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_BLOCK", false);
        }
        try {
            if (!consumeEgressBudget(bytes.length)) return false;
            HttpResponse<String> response = sendBytes(normalizePeerUrl() + "/sync/v1/blocks/" + hash, bytes);
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception exception) {
            return false;
        }
    }

    private HttpResponse<String> sendBytes(String url, byte[] bytes) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .header("X-WESP-Node-Id", nodeId())
                .header("X-WESP-Request-Id", UUID.randomUUID().toString())
                .header("X-WESP-Body-SHA256", sha256(bytes));
        effectiveAuthTokenOptional().ifPresent(value ->
                builder.header("Authorization", "Bearer " + value));
        return httpClient.send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private synchronized boolean consumeEgressBudget(long bytes) {
        if (bytes < 0) return false;
        long limit = maxEgressBytesPerMinute;
        if (limit <= 0) return true;
        long nowMillis = System.currentTimeMillis();
        if (budgetWindowStartMillis == 0 || nowMillis - budgetWindowStartMillis >= 60_000L) {
            budgetWindowStartMillis = nowMillis;
            budgetUsed = 0;
        }
        if (bytes > limit - budgetUsed) return false;
        budgetUsed += bytes;
        return true;
    }

    /** Periodic delivery is deliberately finite-batch and best effort. */
    @Scheduled(every = "30s", identity = "wesp-sync-dispatch")
    void dispatch() {
        // An authenticated admin bootstrap may configure a home node entirely
        // through WespRuntimeConfig, without peer/token env overrides.
        // Use the effective configuration here, otherwise the runtime file is
        // only read for request handling and the active scheduler never runs.
        if (!isEnabled() || !hasPeer()) return;
        try {
            negotiateSession();
            pushPending();
            pullChanges();
            if (nodeRoleService.isEdgeNode()) readOnlyState.markPrimaryOnline();
        } catch (Exception exception) {
            if (nodeRoleService.isEdgeNode()) {
                readOnlyState.markPrimaryOffline("WESP 同步网络不可用，当前边缘节点只读");
            }
            LOG.debug("WESP 同步周期失败，保留本地 outbox 等待下次重试: {}", safeError(exception.getMessage()));
        }
    }

    @Scheduled(every = "1m", identity = "wesp-sync-apply-retry")
    @Transactional
    void retryReceivedOperations() {
        List<WespSyncOperation> received = WespSyncOperation.find(
                "status = 'RECEIVED' order by rowId").range(0, MAX_BATCH_OPERATIONS - 1).list();
        for (WespSyncOperation operation : received) {
            try {
                if ("SYNC_REQUEST".equals(operation.entityType)) {
                    applyFullSyncRequest(operation);
                } else if ("MEDIA_MANIFEST".equals(operation.entityType)) {
                    if (!saveManifestInTransaction(operation.payload)) {
                        operation.status = "CONFLICT";
                        operation.lastError = "media manifest revision conflict";
                        operation.updatedAt = now();
                        continue;
                    }
                } else {
                    syncDataApplyService.apply(toSyncRequest(operation));
                    if ("MEDIA".equals(operation.entityType) && "UPSERT".equals(operation.action)) {
                        materializeStoredManifest(operation.entityId);
                    }
                }
                operation.status = "APPLIED";
                operation.appliedAt = now();
                operation.updatedAt = operation.appliedAt;
                operation.lastError = null;
            } catch (WespProtocolException exception) {
                operation.status = "REJECTED";
                operation.lastError = exception.code;
                operation.updatedAt = now();
            } catch (RuntimeException exception) {
                operation.lastError = safeError(exception.getMessage());
                operation.updatedAt = now();
            }
        }
    }

    private void pushPending() throws Exception {
        List<WespSyncOperation> pending = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                WespSyncOperation.find(
                        "nodeId = ?1 and status = 'APPLIED' and sentAt is null order by rowId", nodeId())
                        .range(0, MAX_BATCH_OPERATIONS - 1).list());
        if (pending.isEmpty()) return;
        ObjectNode root = mapper.createObjectNode();
        root.put("batch_id", UUID.randomUUID().toString());
        ArrayNode operations = root.putArray("operations");
        List<WespSyncOperation> selected = new ArrayList<>();
        for (WespSyncOperation operation : pending) {
            operations.add(operationJson(operation));
            if (mapper.writeValueAsBytes(root).length > MAX_BATCH_BYTES) {
                operations.remove(operations.size() - 1);
                break;
            }
            selected.add(operation);
        }
        if (selected.isEmpty()) throw new IOException("WESP operation exceeds batch limit");
        String body = mapper.writeValueAsString(root);
        if (!consumeEgressBudget(body.getBytes(StandardCharsets.UTF_8).length)) return;
        HttpResponse<String> response = send("PUT", "/sync/v1/batches/" + root.get("batch_id").asText(), body,
                "application/json");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WESP batch HTTP " + response.statusCode());
        }
        JsonNode responseBody = parseObject(response.body());
        JsonNode results = responseBody.get("results");
        if (results == null || !results.isArray()) throw new IOException("WESP batch response missing results");
        List<WespSyncOperation> accepted = new ArrayList<>();
        List<WespSyncOperation> rejected = new ArrayList<>();
        List<WespSyncOperation> conflicts = new ArrayList<>();
        for (JsonNode result : results) {
            String id = text(result, "op_id");
            String status = text(result, "status");
            WespSyncOperation match = selected.stream().filter(item -> item.opId.equals(id)).findFirst().orElse(null);
            if (match == null) continue;
            if ("REJECTED".equals(status)) rejected.add(match);
            else if ("CONFLICT".equals(status)) conflicts.add(match);
            else accepted.add(match);
        }
        markSent(accepted);
        markRejected(rejected);
        markConflict(conflicts);
    }

    private void pullChanges() throws Exception {
        String peer = normalizePeerUrl();
        if (peer == null) return;
        String peerId = sha256(peer);
        long after = cursor(peerId);
        if (consumeFullSyncRequested(peerId)) {
            after = 0;
            LOG.info("WESP 收到全量同步请求，重新从游标 0 拉取 peer={}", peer);
        }
        String url = peer + "/sync/v1/changes?after=" + after + "&limit=" + Math.min(maxPullItems, MAX_BATCH_OPERATIONS);
        HttpResponse<String> response = sendAbsolute("GET", url, null, "application/json");
        if (response.statusCode() == 410) {
            LOG.warn("WESP 变更游标已过期，需实现快照恢复；保留本地 outbox");
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WESP changes HTTP " + response.statusCode());
        }
        JsonNode root = parseObject(response.body());
        JsonNode items = root.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                JsonNode signed = item.has("signed") ? item.get("signed") : item;
                Map<String, Object> result = receivePulledOperation(signed,
                        item.has("signed") ? text(item, "signature") : null);
                String entityType = text(signed, "entity_type");
                if ("MEDIA_MANIFEST".equals(entityType)
                        && List.of("APPLIED", "DUPLICATE").contains(result.get("status"))) {
                    hydrateManifest(signed);
                }
            }
        }
        String nextText = text(root, "next_cursor");
        if (nextText != null) saveCursor(peerId, Long.parseLong(nextText));
    }

    private Map<String, Object> receivePulledOperation(JsonNode signed, String signature) {
        return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> receiveOperation(signed, signature));
    }

    private void applyFullSyncRequest(WespSyncOperation operation) {
        JsonNode request = parseObject(operation.payload);
        if (!nodeId().equals(text(request, "target_node_id"))) {
            throw new WespProtocolException(Response.Status.FORBIDDEN, "SYNC_TARGET_MISMATCH", false);
        }
        String peerId = text(request, "peer_id");
        if (peerId == null || !peerId.matches("[0-9a-f]{64}")) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "SYNC_PEER_INVALID", false);
        }
        markFullSyncRequested(peerId);
    }

    private void hydrateManifest(JsonNode signed) throws Exception {
        JsonNode manifest = signed.get("payload");
        if (manifest == null || !manifest.isObject()) return;
        JsonNode blocks = manifest.get("blocks");
        if (blocks == null || !blocks.isArray()) return;
        String peer = normalizePeerUrl();
        if (peer == null) return;
        for (JsonNode item : blocks) {
            String hash = text(item, "hash");
            long expectedSize = item.path("size").asLong(-1);
            if (hash == null || expectedSize < 1 || expectedSize > MAX_BLOCK_BYTES) continue;
            if (getBlock(hash) != null) continue;
            HttpResponse<byte[]> response = getBytesAbsolute(peer + "/sync/v1/blocks/" + hash);
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length != expectedSize || !sha256(response.body()).equals(hash)) {
                throw new IOException("WESP manifest block unavailable: " + hash);
            }
            byte[] bytes = response.body();
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> putBlock(hash, bytes));
        }
        if (!materializeIfComplete(manifest)) {
            throw new IOException("WESP manifest block missing after hydration");
        }
    }

    private boolean materializeIfComplete(JsonNode manifest) throws IOException {
        if (manifest == null || !manifest.isObject()) return false;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
        long total = 0;
        for (JsonNode item : manifest.withArray("blocks")) {
            String hash = text(item, "hash");
            byte[] bytes = hash == null ? null : getBlock(hash);
            if (bytes == null) return false;
            digest.update(bytes);
            total += bytes.length;
        }
        if (total != manifest.path("file_size").asLong(-1)
                || !sha256(digest.digest()).equals(text(manifest, "file_hash"))) {
            throw new IOException("WESP manifest file digest mismatch");
        }
        materializeManifest(manifest);
        return true;
    }

    private void materializeStoredManifest(String mediaId) {
        WespSyncManifest stored = WespSyncManifest.find(
                "mediaId = ?1 and variant = 'ORIGINAL'", mediaId).firstResult();
        if (stored == null) return;
        try {
            materializeIfComplete(mapper.readTree(stored.payload));
        } catch (Exception exception) {
            LOG.debug("WESP 媒体元数据已到达，但清单尚未完整: {}", exception.getMessage());
        }
    }

    private void materializeManifest(JsonNode manifest) throws IOException {
        String mediaId = text(manifest, "media_id");
        String variantName = text(manifest, "variant");
        if (mediaId == null || variantName == null) return;
        String safeMediaId = mediaId.replaceAll("[^A-Za-z0-9_-]", "_");
        String safeVariant = variantName.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
        String relativePath = "wesp-cache/" + safeMediaId + "/" + safeVariant;
        Path target = Path.of(mediaUploadDir).resolve(relativePath).normalize();
        Path base = Path.of(mediaUploadDir).toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(base)) {
            throw new IOException("WESP materialization path escaped upload directory");
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "wesp-media-", ".part");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            for (JsonNode item : manifest.withArray("blocks")) {
                String hash = text(item, "hash");
                byte[] bytes = hash == null ? null : getBlock(hash);
                if (bytes == null) throw new IOException("WESP block missing while materializing: " + hash);
                output.write(bytes);
            }
        }
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        if (!"ORIGINAL".equalsIgnoreCase(variantName)) return;
        try {
            long numericMediaId = Long.parseLong(mediaId);
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                Media media = Media.findById(numericMediaId);
                if (media != null && media.version != null) {
                    storageService.updateVariantStatus(media.id, storageService.getPrimaryStorageClassName(),
                            VariantType.ORIGINAL, "synced", relativePath, manifest.path("file_size").asLong(),
                            media.version);
                }
            });
        } catch (NumberFormatException ignored) {
            LOG.debug("WESP 媒体 ID 非数字，已保留块缓存但未接入现有 Media 下载器: {}", mediaId);
        }
    }

    void markSent(List<WespSyncOperation> operations) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            OffsetDateTime timestamp = now();
            for (WespSyncOperation operation : operations) {
                entityManager.createQuery("update WespSyncOperation set sentAt = :sentAt, updatedAt = :updatedAt where opId = :opId")
                        .setParameter("sentAt", timestamp).setParameter("updatedAt", timestamp)
                        .setParameter("opId", operation.opId).executeUpdate();
            }
        });
    }

    void markRejected(List<WespSyncOperation> operations) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            OffsetDateTime timestamp = now();
            for (WespSyncOperation operation : operations) {
                entityManager.createQuery("update WespSyncOperation set status = 'REJECTED_REMOTE', lastError = :error, updatedAt = :updatedAt where opId = :opId")
                        .setParameter("error", "receiver rejected operation").setParameter("updatedAt", timestamp)
                        .setParameter("opId", operation.opId).executeUpdate();
            }
        });
    }

    void markConflict(List<WespSyncOperation> operations) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            OffsetDateTime timestamp = now();
            for (WespSyncOperation operation : operations) {
                entityManager.createQuery("update WespSyncOperation set status = 'CONFLICT', lastError = :error, updatedAt = :updatedAt where opId = :opId")
                        .setParameter("error", "receiver reported concurrent revision")
                        .setParameter("updatedAt", timestamp).setParameter("opId", operation.opId).executeUpdate();
            }
        });
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        String peer = normalizePeerUrl();
        if (peer == null) throw new IOException("WESP peer URL 未配置");
        return sendAbsolute(method, peer + path, body, contentType);
    }

    private HttpResponse<String> sendAbsolute(String method, String url, String body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("X-WESP-Node-Id", nodeId())
                .header("X-WESP-Request-Id", UUID.randomUUID().toString());
        builder.header("X-WESP-Body-SHA256", sha256(body == null ? "" : body));
        effectiveAuthTokenOptional().ifPresent(value ->
                builder.header("Authorization", "Bearer " + value));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", contentType).method(method,
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> getBytesAbsolute(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Accept", "application/octet-stream")
                .header("X-WESP-Node-Id", nodeId())
                .header("X-WESP-Request-Id", UUID.randomUUID().toString());
        effectiveAuthTokenOptional().ifPresent(value ->
                builder.header("Authorization", "Bearer " + value));
        return httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String normalizePeerUrl() {
        // An authenticated bootstrap is the node's active enrollment. It must
        // take precedence over an image's stale development/default peer.
        String value = runtimeConfig.peerUrl().filter(item -> !item.isBlank())
                .orElseGet(() -> configuredPeerUrl.orElse(""))
                .trim();
        if (value.isBlank()) return null;
        if (!value.startsWith("https://") && !value.startsWith("http://")) return null;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private ObjectNode operationJson(WespSyncOperation operation) {
        ObjectNode signed = mapper.createObjectNode();
        signed.put("protocol_major", 1);
        signed.put("schema_version", 1);
        signed.put("op_id", operation.opId);
        signed.put("node_id", operation.nodeId);
        signed.put("actor_id", operation.nodeId);
        signed.put("tenant_id", effectiveTenantId());
        signed.put("dataset_id", operation.datasetId);
        signed.put("incarnation", operation.incarnation);
        signed.put("seq", Long.toString(operation.seq));
        signed.put("entity_type", operation.entityType);
        signed.put("operation", operation.action);
        signed.put("entity_id", operation.entityId);
        try { signed.set("payload", mapper.readTree(operation.payload)); }
        catch (Exception exception) { signed.putObject("payload"); }
        signed.put("payload_hash", operation.payloadHash);
        if ("MEDIA_MANIFEST".equals(operation.entityType)) {
            try {
                String manifestId = text(mapper.readTree(operation.payload), "manifest_id");
                if (manifestId != null) signed.put("manifest_id", manifestId);
                else signed.putNull("manifest_id");
            } catch (Exception ignored) {
                signed.putNull("manifest_id");
            }
        } else {
            signed.putNull("manifest_id");
        }
        signed.put("auth_epoch", "1");
        signed.put("key_id", operation.nodeId);
        signed.putObject("hlc").put("physical_ms", Long.toString(operation.createdAt == null
                ? System.currentTimeMillis() : operation.createdAt.toInstant().toEpochMilli())).put("logical", "0");
        ArrayNode context = signed.putArray("context");
        if (operation.seq > 1) {
            ObjectNode previous = context.addObject();
            previous.put("actor_id", operation.nodeId);
            previous.put("incarnation", operation.incarnation);
            previous.put("seq", Long.toString(operation.seq - 1));
        }
        signed.putArray("required_capabilities");
        signed.putObject("extensions");
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.set("signed", signed);
        wrapped.put("signature", signOperation(signed));
        return wrapped;
    }

    private void negotiateSession() throws Exception {
        long nowMillis = System.currentTimeMillis();
        if (sessionNegotiated && nowMillis - lastSessionMillis < 60_000L) {
            // Keep the local admin view fresh while avoiding a session request
            // on every 30-second cycle. The remote peer receives a real
            // request at least once per minute, before its 90-second timeout.
            recordPeerSession(nodeId());
            return;
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("protocol_major", 1);
        request.put("protocol_minor", 0);
        request.put("schema_version", 1);
        request.put("node_id", nodeId());
        request.put("tenant_id", effectiveTenantId());
        request.put("incarnation", incarnation);
        request.putArray("capabilities").add("blocks").add("manifests").add("changes").add("idempotent_batches");
        request.putArray("datasets").addObject().put("dataset_id", effectiveDatasetId())
                .put("received_frontier", "0").put("applied_frontier", "0");
        request.putObject("limits").put("max_batch_bytes", MAX_BATCH_BYTES)
                .put("max_operation_bytes", MAX_OPERATION_BYTES).put("max_block_bytes", MAX_BLOCK_BYTES);
        HttpResponse<String> response = send("POST", "/sync/v1/sessions", mapper.writeValueAsString(request),
                "application/json");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WESP session HTTP " + response.statusCode());
        }
        parseObject(response.body());
        sessionNegotiated = true;
        lastSessionMillis = System.currentTimeMillis();
        // A home admin may also have a local EdgeNode record created by the
        // authenticated bootstrap. Keep that view in sync with the successful
        // outbound session; the public peer independently records the same
        // session for its central node list.
        recordPeerSession(nodeId());
    }

    private String signOperation(JsonNode signed) {
        String token = effectiveAuthToken();
        if (token.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prefix = "WESP/1/op\n".getBytes(StandardCharsets.UTF_8);
            byte[] canonical = canonicalBytes(signed);
            byte[] input = new byte[prefix.length + canonical.length];
            System.arraycopy(prefix, 0, input, 0, prefix.length);
            System.arraycopy(canonical, 0, input, prefix.length, canonical.length);
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 WESP 操作签名", exception);
        }
    }

    private boolean verifyOperationSignature(JsonNode signed, String signature) {
        if (signature.length() != 64 || !signature.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(signOperation(signed).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private SyncDataRequest toSyncRequest(WespSyncOperation operation) {
        long numericId = parseEntityId(operation.entityId, operation.payload);
        String payload = operation.payload;
        if ("CLUSTER_PUBLIC_KEY".equals(operation.entityType)) {
            try {
                JsonNode value = mapper.readTree(payload);
                if (value != null && value.isTextual()) payload = value.asText();
            } catch (Exception ignored) {
                // Keep the original payload so the existing apply validation handles it.
            }
        }
        return SyncDataRequest.newBuilder().setAction(operation.action).setEntityType(operation.entityType)
                .setPayload(payload).setEntityId(numericId).setEntityVersion(operation.seq).build();
    }

    private long parseEntityId(String entityId, String payload) {
        try { return Long.parseLong(entityId); } catch (NumberFormatException ignored) { }
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode candidate = root.path("id");
            if (!candidate.isNumber()) candidate = root.path("post").path("id");
            if (candidate.isNumber()) return candidate.asLong();
        } catch (Exception ignored) { }
        return 0L;
    }

    private JsonNode parseObject(String body) {
        try {
            JsonNode parsed = readTree(body == null ? "" : body);
            if (parsed == null || !parsed.isObject()) throw new Exception("not object");
            validateDepth(parsed, 0);
            return parsed;
        } catch (Exception exception) {
            throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_JSON", false);
        }
    }

    private String normalizePayload(String payload, boolean allowScalarText) {
        String source = payload == null || payload.isBlank() ? "{}" : payload;
        JsonNode parsed;
        try {
            parsed = readTree(source);
            if (parsed == null) throw new Exception("empty");
            validateDepth(parsed, 0);
        } catch (Exception exception) {
            if (!allowScalarText) {
                throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_PAYLOAD", false);
            }
            // Cluster public-key events historically carry a PEM string, not an object.
            parsed = mapper.getNodeFactory().textNode(source);
        }
        try { return mapper.writeValueAsString(canonicalize(parsed)); }
        catch (Exception exception) { throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_PAYLOAD", false); }
    }

    private byte[] canonicalBytes(JsonNode value) throws IOException {
        return mapper.writeValueAsBytes(canonicalize(value));
    }

    private JsonNode readTree(String source) throws IOException {
        return mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(source);
    }

    private void validateDepth(JsonNode value, int depth) {
        if (depth > 16) throw new WespProtocolException(Response.Status.BAD_REQUEST, "JSON_TOO_DEEP", false);
        if (value == null) return;
        if (value.isArray()) {
            for (JsonNode item : value) validateDepth(item, depth + 1);
        } else if (value.isObject()) {
            value.elements().forEachRemaining(item -> validateDepth(item, depth + 1));
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            for (JsonNode item : value) array.add(canonicalize(item));
            return array;
        }
        ObjectNode object = mapper.createObjectNode();
        java.util.TreeSet<String> fields = new java.util.TreeSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) object.set(field, canonicalize(value.get(field)));
        return object;
    }

    private long nextSequence() {
        return ((Number) entityManager.createNativeQuery("select nextval('wesp_operation_seq')").getSingleResult()).longValue();
    }

    private String effectiveDatasetId() {
        return runtimeConfig.datasetId().filter(value -> !value.isBlank())
                .orElse(datasetId == null || datasetId.isBlank() ? "public" : datasetId.trim());
    }

    private String effectiveTenantId() {
        return runtimeConfig.tenantId().filter(value -> !value.isBlank())
                .orElse(tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim());
    }

    private Optional<String> effectiveAuthTokenOptional() {
        return runtimeConfig.authToken().filter(value -> !value.isBlank())
                .or(() -> authToken.filter(value -> !value.isBlank()))
                .map(String::trim).filter(value -> !value.isBlank());
    }

    private String effectiveAuthToken() {
        return effectiveAuthTokenOptional().orElse("");
    }

    private static Map<String, Object> result(String opId, String status, String error) {
        if (error == null) return Map.of("op_id", opId == null ? "" : opId, "status", status);
        return Map.of("op_id", opId == null ? "" : opId, "status", status, "error", error);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static boolean isUuid(String value) { try { UUID.fromString(value); return true; } catch (Exception e) { return false; } }

    private static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " 无效");
    }

    private static void validateHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new WespProtocolException(Response.Status.BAD_REQUEST, "INVALID_HASH", false);
    }

    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return "WESP operation failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(512, normalized.length()));
    }

    public static final class WespProtocolException extends RuntimeException {
        public final int statusCode;
        public final String code;
        public final boolean retryable;
        public WespProtocolException(Response.Status status, String code, boolean retryable) {
            this(status.getStatusCode(), code, retryable);
        }
        public WespProtocolException(int statusCode, String code, boolean retryable) {
            super(code);
            this.statusCode = statusCode; this.code = code; this.retryable = retryable;
        }
    }
}
