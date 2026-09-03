package com.biliwind.blog.controller.api.internal;

import com.biliwind.blog.service.ai.CodexCreatorContentOperationService;
import com.biliwind.blog.service.ai.CodexCreatorContentService;
import com.biliwind.blog.service.ai.CodexCreatorHmac;
import com.biliwind.blog.service.ai.CodexCreatorInboundRequestVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/** Private signed API used by the Codex Creator MCP content tools. */
@Path("/api/internal/integrations/codex-creator/content")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class CodexCreatorContentResource {
    @Inject
    ObjectMapper mapper;

    @Inject
    CodexCreatorInboundRequestVerifier verifier;

    @Inject
    CodexCreatorContentOperationService operations;

    @Inject
    CodexCreatorContentService contentService;

    @POST
    public Response execute(String body,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Client-Id") String clientId,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Timestamp") String timestamp,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Nonce") String nonce,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Body-SHA256") String bodyDigest,
                            @jakarta.ws.rs.HeaderParam("X-Codex-Signature") String signature) {
        if (!verifier.verify(clientId, timestamp, nonce, bodyDigest, signature, body)) {
            return error(Response.Status.UNAUTHORIZED, "签名无效或请求已重放");
        }

        JsonNode request;
        try {
            request = mapper.readTree(body == null ? "{}" : body);
        } catch (Exception exception) {
            return error(Response.Status.BAD_REQUEST, "请求 JSON 无效");
        }
        if (request == null || !request.isObject()) {
            return error(Response.Status.BAD_REQUEST, "请求必须是 JSON 对象");
        }

        String operation = text(request, "operation");
        String requestKey = text(request, "idempotencyKey");
        String traceId = text(request, "traceId");
        JsonNode input = request.get("input");
        if (!CodexCreatorContentService.ALLOWED_OPERATIONS.contains(operation)
                || requestKey == null || requestKey.isBlank() || requestKey.length() > 256
                || input == null || !input.isObject()) {
            return error(Response.Status.BAD_REQUEST, "Codex 内容请求参数无效");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = nonce;
        }
        if (traceId.length() > 160) {
            return error(Response.Status.BAD_REQUEST, "traceId 过长");
        }

        String requestDigestForIdempotency = CodexCreatorHmac.bodyDigest(
                operation + "\n" + input + "\n" + requestKey);
        CodexCreatorContentOperationService.Claim claim;
        try {
            claim = operations.claim(requestKey, operation, requestDigestForIdempotency);
        } catch (WebApplicationException exception) {
            return error(status(exception), safeMessage(exception.getMessage()));
        } catch (RuntimeException exception) {
            Log.warn("Unable to claim Codex content idempotency record", exception);
            return error(Response.Status.SERVICE_UNAVAILABLE, "Codex 内容幂等服务暂不可用");
        }

        if (claim.status() == CodexCreatorContentOperationService.ClaimStatus.CACHED_SUCCESS) {
            return success(claim.data());
        }
        if (claim.status() == CodexCreatorContentOperationService.ClaimStatus.CACHED_FAILURE) {
            return error(Response.Status.BAD_REQUEST,
                    claim.errorMessage() == null ? "Codex 内容请求失败" : claim.errorMessage());
        }
        if (claim.status() == CodexCreatorContentOperationService.ClaimStatus.BUSY) {
            return error(Response.Status.CONFLICT, "相同 Codex 内容请求正在处理中");
        }

        try {
            Map<String, Object> data = contentService.execute(operation, input, traceId);
            operations.complete(requestKey, data);
            return success(data);
        } catch (WebApplicationException exception) {
            String message = safeMessage(exception.getMessage());
            operations.fail(requestKey, message);
            return error(status(exception), message);
        } catch (IllegalArgumentException exception) {
            String message = safeMessage(exception.getMessage());
            operations.fail(requestKey, message);
            return error(Response.Status.BAD_REQUEST, message);
        } catch (RuntimeException exception) {
            Log.warn("Codex content operation failed", exception);
            operations.fail(requestKey, "Codex 内容操作失败");
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Codex 内容操作失败");
        }
    }

    private Response success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return Response.ok(response).build();
    }

    private Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of(
                "success", false,
                "message", message == null || message.isBlank() ? "Codex 内容请求失败" : message)).build();
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

    private static Response.Status status(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response == null || response.getStatus() < 400 || response.getStatus() >= 600) {
            return Response.Status.BAD_REQUEST;
        }
        Response.Status resolved = Response.Status.fromStatusCode(response.getStatus());
        return resolved == null ? Response.Status.BAD_REQUEST : resolved;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Codex 内容请求失败";
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 1_000 ? normalized.substring(0, 1_000) : normalized;
    }
}
