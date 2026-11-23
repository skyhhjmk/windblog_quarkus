package com.biliwind.blog.controller.api.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;
import java.util.List;

public class AdminCommentDtos {

    @RegisterForReflection
    public record AiAuditState(
            String phase,
            String label,
            String detail,
            boolean stuck,
            boolean canAbort,
            int attemptCount,
            int maxAttempts,
            Long elapsedSeconds,
            String lastError,
            OffsetDateTime updatedAt,
            OffsetDateTime nextRetryAt) {
    }

    @RegisterForReflection
    public record AdminCommentItem(
            Long id,
            Long postId,
            String postTitle,
            Long userId,
            String userName,
            String guestEmail,
            String content,
            Long parentId,
            short status,
            short auditStatus,
            Object aiReviewData,
            boolean isReviewing,
            AiAuditState aiAuditState,
            OffsetDateTime createdAt,
            Long quoteId,
            String quoteType,
            String quoteText,
            Object quoteAnchorData,
            Short quoteStatus) {
    }

    @RegisterForReflection
    public record CommentUpdateRequest(
            Short status,
            String content,
            Boolean sendEmailNotification) {
    }

    @RegisterForReflection
    public record CommentBatchRequest(
            List<Long> ids,
            String action,
            Boolean sendEmailNotification) {
    }

    @RegisterForReflection
    public record CommentBatchResult(
            int requested,
            int succeeded,
            int skipped,
            int failed,
            List<Long> failedIds,
            String message) {
    }
}
