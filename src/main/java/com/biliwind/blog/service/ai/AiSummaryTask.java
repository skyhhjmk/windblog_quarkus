package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * AI 摘要任务数据传输对象
 * 
 * @param postId 文章 ID
 * @param content 文章内容（键为语言，值为内容）
 * @param priority 优先级（0: 高，1: 中，2: 低）
 * @param retryCount 当前重试次数（用于死信队列处理）
 */
public record AiSummaryTask(
        @JsonProperty("postId") Long postId,
        @JsonProperty("content") Map<String, String> content,
        @JsonProperty("priority") int priority, // 0: High, 1: Medium, 2: Low
        @JsonProperty("retryCount") int retryCount, // 重试次数，初始为 0
        @JsonProperty("performedBy") Long performedBy // 执行人 ID
) {
    /**
     * 构造函数，默认重试次数为 0
     */
    public AiSummaryTask(Long postId, Map<String, String> content, int priority, Long performedBy) {
        this(postId, content, priority, 0, performedBy);
    }
    
    /**
     * 创建一个新的任务实例，重试次数 +1
     */
    public AiSummaryTask withRetry() {
        return new AiSummaryTask(this.postId, this.content, this.priority, this.retryCount + 1, this.performedBy);
    }
}
