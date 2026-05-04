package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI 审核任务数据传输对象
 *
 * @param commentId  评论 ID
 * @param content    评论内容
 * @param retryCount 当前重试次数
 */
public record AiAuditTask(
        @JsonProperty("commentId") Long commentId,
        @JsonProperty("content") String content,
        @JsonProperty("retryCount") int retryCount
) {
    public AiAuditTask(Long commentId, String content) {
        this(commentId, content, 0);
    }
}
