package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * AI 审核任务数据传输对象
 *
 * @param commentId  评论 ID
 * @param content    评论内容
 * @param retryCount 当前重试次数
 */
@RegisterForReflection
public record AiAuditTask(
        @JsonProperty("commentId") Long commentId,
        @JsonProperty("content") String content,
        @JsonProperty("retryCount") int retryCount
) {
    /**
     * Keep the property-based creator available to Jackson in native images.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AiAuditTask(
            @JsonProperty("commentId") Long commentId,
            @JsonProperty("content") String content,
            @JsonProperty("retryCount") int retryCount) {
        this.commentId = commentId;
        this.content = content;
        this.retryCount = retryCount;
    }

    public AiAuditTask(Long commentId, String content) {
        this(commentId, content, 0);
    }
}
