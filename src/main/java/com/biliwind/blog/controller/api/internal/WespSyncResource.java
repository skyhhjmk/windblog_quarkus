package com.biliwind.blog.controller.api.internal;

import com.biliwind.blog.service.edge.WespSyncService;
import com.biliwind.blog.service.edge.WespActivePollChannel;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.edge.PrimaryRoutedHttpExecutor;
import com.biliwind.blog.service.edge.RoutedHttpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import io.smallrye.common.annotation.Blocking;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Internal WESP v1 receiver. It never opens a connection to a peer. */
@Path("/sync/v1")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "WESP internal synchronization")
public class WespSyncResource {
    @Inject WespSyncService sync;
    @Inject WespActivePollChannel activePoll;
    @Inject NodeRoleService nodeRoleService;
    @Inject PrimaryRoutedHttpExecutor primaryRoutedHttpExecutor;
    @Inject ObjectMapper mapper;
    @Context HttpHeaders requestHeaders;

    @GET
    @Path("/active-poll/next")
    @Blocking
    @Operation(summary = "Hold a primary-initiated request channel to a public edge")
    public CompletionStage<Response> nextActivePoll(@HeaderParam("Authorization") String authorization,
                                                     @HeaderParam("X-WESP-Node-Id") String nodeId,
                                                     @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, "");
        if (auth != null) return java.util.concurrent.CompletableFuture.completedFuture(auth);
        return activePoll.poll(nodeId);
    }

    @POST
    @Path("/active-poll/results/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Return a primary-executed response over the outbound channel")
    public Response activePollResult(@PathParam("id") String id, String body,
                                     @HeaderParam("Authorization") String authorization,
                                     @HeaderParam("X-WESP-Node-Id") String nodeId,
                                     @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        if (body == null || body.length() > 15_000_000 || !isUuid(id)) {
            return error(Response.Status.BAD_REQUEST, requestId, "INVALID_ACTIVE_POLL_RESULT", false);
        }
        try {
            return activePoll.complete(nodeId, id, mapper.readTree(body))
                    ? Response.noContent().build()
                    : error(Response.Status.CONFLICT, requestId, "ACTIVE_POLL_RESULT_NOT_PENDING", false);
        } catch (Exception exception) {
            return error(Response.Status.BAD_REQUEST, requestId, "INVALID_ACTIVE_POLL_RESULT", false);
        }
    }

    @POST
    @Path("/sessions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Negotiate an outbound WESP session")
    public Response session(String body, @HeaderParam("Authorization") String authorization,
                            @HeaderParam("X-WESP-Node-Id") String nodeId,
                            @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        try {
            sync.validateSessionRequest(body);
            sync.recordPeerSession(nodeId);
            return Response.ok(Map.of("session_id", UUID.randomUUID().toString(), "protocol_major", 1,
                    "protocol_minor", 0,
                    "schema_version", 1, "authority_mode", "single", "authority_epoch", "1",
                    "required_public_copies", 1, "retry_after_seconds", 30,
                    "accepted_capabilities", new String[]{"blocks", "manifests", "changes", "idempotent_batches"})).build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        }
    }

    @POST
    @Path("/full-sync")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Request a full public snapshot from the primary")
    public Response fullSync(String body, @HeaderParam("Authorization") String authorization,
                             @HeaderParam("X-WESP-Node-Id") String nodeId,
                             @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        if (!nodeRoleService.isPrimaryNode()) {
            return error(Response.Status.CONFLICT, requestId, "FULL_SYNC_TARGET_NOT_PRIMARY", true);
        }
        try {
            JsonNode request = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            boolean force = request.path("force").asBoolean(false);
            sync.triggerFullSync(nodeId, force);
            return Response.accepted().build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, requestId, "INVALID_FULL_SYNC_REQUEST", false);
        } catch (Exception exception) {
            return error(Response.Status.SERVICE_UNAVAILABLE, requestId, "FULL_SYNC_UNAVAILABLE", true);
        }
    }

    @POST
    @Path("/requests")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Execute an outbound edge write on the primary")
    public Response routedRequest(String body, @HeaderParam("Authorization") String authorization,
                                  @HeaderParam("X-WESP-Node-Id") String nodeId,
                                  @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        if (activePoll.isEnabled()) {
            return error(Response.Status.CONFLICT, requestId, "ACTIVE_POLL_REQUIRES_PRIMARY_OUTBOUND", false);
        }
        if (!nodeRoleService.isPrimaryNode()) {
            return error(Response.Status.CONFLICT, requestId, "REQUEST_TARGET_NOT_PRIMARY", true);
        }
        try {
            JsonNode request = mapper.readTree(body);
            String method = text(request, "method");
            String path = text(request, "path");
            if (method == null || path == null || !path.startsWith("/") || path.startsWith("/sync/")) {
                return error(Response.Status.BAD_REQUEST, requestId, "INVALID_ROUTED_REQUEST", false);
            }
            method = method.toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                return error(Response.Status.BAD_REQUEST, requestId, "INVALID_ROUTED_METHOD", false);
            }
            String encoded = text(request, "body");
            byte[] requestBody = encoded == null || encoded.isBlank() ? new byte[0] : Base64.getDecoder().decode(encoded);
            if (requestBody.length > 10_485_760) {
                return error(Response.Status.REQUEST_ENTITY_TOO_LARGE, requestId, "ROUTED_BODY_TOO_LARGE", false);
            }
            Map<String, String> headers = new java.util.HashMap<>();
            JsonNode headerNode = request.get("headers");
            if (headerNode != null && headerNode.isObject()) {
                headerNode.fields().forEachRemaining(entry -> {
                    if (entry.getKey() != null && entry.getValue().isTextual()) headers.put(entry.getKey(), entry.getValue().asText());
                });
            }
            RoutedHttpExchange.Response routed = primaryRoutedHttpExecutor.execute(new RoutedHttpExchange.Request(
                    method, text(request, "path"), text(request, "query"), headers, requestBody));
            ObjectNode result = mapper.createObjectNode();
            result.put("status", routed.status());
            ObjectNode responseHeaders = result.putObject("headers");
            if (routed.headers() != null) routed.headers().forEach(responseHeaders::put);
            result.put("body", Base64.getEncoder().encodeToString(routed.body() == null ? new byte[0] : routed.body()));
            result.put("errorMessage", routed.errorMessage() == null ? "" : routed.errorMessage());
            return Response.ok(result).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, requestId, "INVALID_ROUTED_REQUEST", false);
        } catch (Exception exception) {
            return error(Response.Status.BAD_GATEWAY, requestId, "ROUTED_REQUEST_FAILED", true);
        }
    }

    @PUT
    @Path("/batches/{batchId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Accept a durable operation batch")
    public Response batch(@PathParam("batchId") String batchId, String body,
                           @HeaderParam("Authorization") String authorization,
                           @HeaderParam("X-WESP-Node-Id") String nodeId,
                           @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        try {
            Map<String, Object> result = sync.receiveBatch(batchId, body);
            int status = Boolean.TRUE.equals(result.get("duplicate"))
                    ? Response.Status.OK.getStatusCode() : Response.Status.CREATED.getStatusCode();
            return Response.status(status).entity(result).build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        } catch (RuntimeException exception) {
            return error(Response.Status.SERVICE_UNAVAILABLE, requestId, "SYNC_UNAVAILABLE", true);
        }
    }

    @GET
    @Path("/changes")
    public Response changes(@QueryParam("after") String after,
                            @QueryParam("limit") String limit,
                            @HeaderParam("Authorization") String authorization,
                            @HeaderParam("X-WESP-Node-Id") String nodeId,
                            @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, "");
        if (auth != null) return auth;
        try {
            long cursor = parseLong(after, 0, Long.MAX_VALUE);
            int pageSize = (int) parseLong(limit, 256, 256);
            return Response.ok(sync.getChanges(cursor, pageSize)).build();
        } catch (RuntimeException exception) {
            return error(Response.Status.BAD_REQUEST, requestId, "INVALID_CURSOR", false);
        }
    }

    @POST
    @Path("/inventory")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response inventory(String body, @HeaderParam("Authorization") String authorization,
                              @HeaderParam("X-WESP-Node-Id") String nodeId,
                              @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        try {
            return Response.ok(sync.inventory(body)).build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        }
    }

    @PUT
    @Path("/receipts/{receiptId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receipt(@PathParam("receiptId") String receiptId, String body,
                            @HeaderParam("Authorization") String authorization,
                            @HeaderParam("X-WESP-Node-Id") String nodeId,
                            @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, body);
        if (auth != null) return auth;
        try {
            return Response.ok(sync.recordReceipt(receiptId, nodeId, body)).build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        }
    }

    @GET
    @Path("/snapshots/{snapshotId}")
    public Response snapshot(@PathParam("snapshotId") String snapshotId,
                             @HeaderParam("Authorization") String authorization,
                             @HeaderParam("X-WESP-Node-Id") String nodeId,
                             @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, "");
        if (auth != null) return auth;
        // v1 keeps the operation log as the source of truth. A compaction job may
        // advertise a signed checkpoint later; never return an unverified snapshot.
        return error(410, requestId, "CHECKPOINT_UNAVAILABLE", true);
    }

    @PUT
    @Path("/blocks/{hash}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response block(@PathParam("hash") String hash, byte[] bytes,
                           @HeaderParam("Authorization") String authorization,
                           @HeaderParam("X-WESP-Node-Id") String nodeId,
                           @HeaderParam("X-WESP-Request-Id") String requestId) {
        Response auth = authorize(authorization, nodeId, requestId, null);
        if (auth != null) return auth;
        String digest = requestHeaders == null ? null : requestHeaders.getHeaderString("X-WESP-Body-SHA256");
        if (digest != null && (!digest.matches("[0-9a-f]{64}")
                || !digest.equals(sha256(bytes == null ? new byte[0] : bytes)))) {
            return error(Response.Status.BAD_REQUEST, requestId, "BODY_HASH_MISMATCH", false);
        }
        if (bytes != null && bytes.length > 2_097_152) return error(Response.Status.REQUEST_ENTITY_TOO_LARGE,
                requestId, "BLOCK_TOO_LARGE", true);
        try {
            return Response.status(Response.Status.CREATED).entity(sync.putBlock(hash, bytes)).build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        }
    }

    @GET
    @Path("/blocks/{hash}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getBlock(@PathParam("hash") String hash,
                             @HeaderParam("Authorization") String authorization,
                             @HeaderParam("X-WESP-Node-Id") String nodeId,
                             @HeaderParam("X-WESP-Request-Id") String requestId,
                             @HeaderParam("Range") String range,
                             @HeaderParam("If-Range") String ifRange) {
        Response auth = authorize(authorization, nodeId, requestId, "");
        if (auth != null) return auth;
        try {
            byte[] data = sync.getBlock(hash);
            if (data == null) return error(Response.Status.NOT_FOUND, requestId, "BLOCK_NOT_FOUND", false);
            Response.ResponseBuilder builder = Response.ok(data);
            if (range != null && !range.isBlank()) {
                if (ifRange == null || !(ifRange.equals(hash) || ifRange.equals("\"" + hash + "\""))) {
                    return error(Response.Status.PRECONDITION_FAILED, requestId, "IF_RANGE_REQUIRED", false);
                }
                long[] bounds = parseRange(range, data.length);
                if (bounds == null) return error(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE,
                        requestId, "INVALID_RANGE", false);
                int start = (int) bounds[0];
                int end = (int) bounds[1];
                byte[] partial = java.util.Arrays.copyOfRange(data, start, end + 1);
                builder = Response.status(Response.Status.PARTIAL_CONTENT).entity(partial)
                        .header("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            return builder.header("ETag", "\"" + hash + "\"")
                    .header("Accept-Ranges", "bytes").header("Cache-Control", "private, max-age=60").build();
        } catch (WespSyncService.WespProtocolException exception) {
            return protocolError(requestId, exception);
        }
    }

    private Response authorize(String authorization, String nodeId, String requestId, String body) {
        if (!sync.isEnabled()) return error(Response.Status.NOT_FOUND, requestId, "DISABLED", false);
        if (nodeId == null || nodeId.isBlank() || nodeId.length() > 100 || !isUuid(requestId)) {
            return error(Response.Status.BAD_REQUEST, requestId, "INVALID_HEADERS", false);
        }
        // The shared token is intentionally checked by the service endpoint boundary.
        // Empty tokens are rejected in production configuration; development can use
        // an explicit token value in application.properties.
        if (!sync.isAuthorized(authorization, nodeId, body)) {
            return error(Response.Status.UNAUTHORIZED, requestId, "UNAUTHORIZED", false);
        }
        String digest = requestHeaders == null ? null : requestHeaders.getHeaderString("X-WESP-Body-SHA256");
        if (body != null && digest != null && (!digest.matches("[0-9a-f]{64}")
                || !digest.equals(sha256(body == null ? "" : body)))) {
            return error(Response.Status.BAD_REQUEST, requestId, "BODY_HASH_MISMATCH", false);
        }
        return null;
    }

    private Response protocolError(String requestId, WespSyncService.WespProtocolException exception) {
        return error(exception.statusCode, requestId, exception.code, exception.retryable);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private Response error(Response.Status status, String requestId, String code, boolean retryable) {
        return error(status.getStatusCode(), requestId, code, retryable);
    }

    private Response error(int statusCode, String requestId, String code, boolean retryable) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("request_id", requestId == null ? "" : requestId);
        error.put("error", Map.of("code", code, "retryable", retryable,
                "message", safeMessage(code)));
        Response.ResponseBuilder builder = Response.status(statusCode).entity(error);
        if (statusCode == Response.Status.TOO_MANY_REQUESTS.getStatusCode()
                || statusCode == Response.Status.SERVICE_UNAVAILABLE.getStatusCode()) {
            builder.header("Retry-After", "30");
        }
        return builder.build();
    }

    private static long parseLong(String value, long fallback, long max) {
        if (value == null || value.isBlank()) return fallback;
        long parsed = Long.parseLong(value);
        if (parsed < 0 || parsed > max) throw new IllegalArgumentException("range");
        return parsed;
    }

    private static long[] parseRange(String value, int length) {
        if (length <= 0 || !value.startsWith("bytes=") || value.substring(6).contains(",")) return null;
        String[] parts = value.substring(6).split("-", -1);
        if (parts.length != 2) return null;
        try {
            long start;
            long end;
            if (parts[0].isBlank()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1L;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isBlank() ? length - 1L : Long.parseLong(parts[1]);
            }
            if (start < 0 || start >= length || end < start) return null;
            end = Math.min(end, length - 1L);
            return new long[]{start, end};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isUuid(String value) { try { UUID.fromString(value); return true; } catch (Exception e) { return false; } }

    private static String safeMessage(String code) { return "WESP request rejected: " + code; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
