package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;

public class AdminCommentDtos {

    public record AdminCommentItem(
            Long id,
            Long postId,
            String postTitle,
            Long userId,
            String userName,
            String content,
            Long parentId,
            short status,
            short auditStatus,
            Object aiReviewData,
            boolean isReviewing,
            OffsetDateTime createdAt) {
    }

    public record CommentUpdateRequest(
            Short status,
            String content) {
    }
}
