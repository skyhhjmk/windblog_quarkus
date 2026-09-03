package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.CodexCreatorContentOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.time.OffsetDateTime;

/** Stores parent-side idempotency state for Codex content mutations. */
@ApplicationScoped
public class CodexCreatorContentOperationService {
    @Inject
    ObjectMapper mapper;

    @Inject
    jakarta.persistence.EntityManager entityManager;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Claim claim(String requestKey, String operation, String requestDigest) {
        validate(requestKey, operation, requestDigest);
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO codex_creator_content_operations
                    (request_key, operation, request_digest, status, created_at, updated_at)
                VALUES (:requestKey, :operation, :requestDigest, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (request_key) DO NOTHING
                """)
                .setParameter("requestKey", requestKey)
                .setParameter("operation", operation)
                .setParameter("requestDigest", requestDigest)
                .executeUpdate();

        CodexCreatorContentOperation operationRecord = findLocked(requestKey);
        if (operationRecord == null) {
            throw new IllegalStateException("无法创建 Codex 内容幂等记录");
        }
        if (!operation.equals(operationRecord.operation)
                || !requestDigest.equals(operationRecord.requestDigest)) {
            throw new BadRequestException("幂等键已绑定其他 Codex 内容请求");
        }
        if (inserted == 1) {
            return new Claim(ClaimStatus.NEW, null, null);
        }
        if ("SUCCEEDED".equals(operationRecord.status)) {
            return new Claim(ClaimStatus.CACHED_SUCCESS, parse(operationRecord.response), null);
        }
        if ("FAILED".equals(operationRecord.status)) {
            return new Claim(ClaimStatus.CACHED_FAILURE, null,
                    operationRecord.errorMessage == null ? "Codex 内容请求失败" : operationRecord.errorMessage);
        }
        return new Claim(ClaimStatus.BUSY, null, null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void complete(String requestKey, Object data) {
        CodexCreatorContentOperation operationRecord = findLocked(requestKey);
        if (operationRecord == null) {
            throw new IllegalStateException("Codex 内容幂等记录不存在");
        }
        operationRecord.status = "SUCCEEDED";
        operationRecord.response = json(data);
        operationRecord.errorMessage = null;
        operationRecord.completedAt = OffsetDateTime.now();
        operationRecord.updatedAt = operationRecord.completedAt;
        operationRecord.persist();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void fail(String requestKey, String message) {
        CodexCreatorContentOperation operationRecord = findLocked(requestKey);
        if (operationRecord == null) {
            return;
        }
        operationRecord.status = "FAILED";
        operationRecord.response = null;
        operationRecord.errorMessage = safeMessage(message);
        operationRecord.completedAt = OffsetDateTime.now();
        operationRecord.updatedAt = operationRecord.completedAt;
        operationRecord.persist();
    }

    private CodexCreatorContentOperation findLocked(String requestKey) {
        PanacheQuery<CodexCreatorContentOperation> query = CodexCreatorContentOperation
                .<CodexCreatorContentOperation>find("requestKey", requestKey)
                .withLock(LockModeType.PESSIMISTIC_WRITE);
        return query.firstResult();
    }

    private JsonNode parse(String response) {
        try {
            return mapper.readTree(response == null || response.isBlank() ? "{}" : response);
        } catch (Exception exception) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? mapper.createObjectNode() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存 Codex 内容响应", exception);
        }
    }

    private static void validate(String requestKey, String operation, String requestDigest) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 256
                || operation == null || operation.isBlank() || operation.length() > 64
                || requestDigest == null || !requestDigest.matches("[0-9a-fA-F]{64}")) {
            throw new BadRequestException("Codex 内容幂等参数无效");
        }
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Codex 内容请求失败";
        }
        return message.length() > 1_000 ? message.substring(0, 1_000) : message;
    }

    public enum ClaimStatus {
        NEW,
        CACHED_SUCCESS,
        CACHED_FAILURE,
        BUSY
    }

    public record Claim(ClaimStatus status, JsonNode data, String errorMessage) {
    }
}
