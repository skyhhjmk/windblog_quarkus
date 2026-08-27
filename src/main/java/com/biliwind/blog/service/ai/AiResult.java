package com.biliwind.blog.service.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 执行结果包装类，包含内容和 Token 消耗统计
 */
public class AiResult {

    /**
     * 生成的内容，通常是多语言 Map
     */
    public Map<String, String> contents = new HashMap<>();

    /**
     * 输入 Token 数
     */
    public int inputTokens = 0;

    /**
     * 输出 Token 数
     */
    public int outputTokens = 0;

    /**
     * 总 Token 数
     */
    public int totalTokens = 0;

    /**
     * 错误消息（如果有）
     */
    public String errorMessage;

    /**
     * AI 审核理由（来自 AI 原始输出的 reason 字段）
     */
    public String reason;

    /**
     * 是否安全 (用于审核功能)
     */
    public Boolean isSafe;

    /**
     * 安全评分 (通常 0-100)
     */
    public Integer score;

    /**
     * 原始输出内容
     */
    public String rawResponse;

    /** Optional provider provenance populated by the Codex Creator adapter. */
    public String provider;
    public String modelId;
    public String reasoningEffort;
    public String taskId;
    public String generationMode;
    public Map<String, Object> provenance = new HashMap<>();

    public AiResult() {
    }

    public AiResult(Map<String, String> contents) {
        this.contents = contents;
    }

    /**
     * 累加另一个结果的 Token 消耗
     */
    public void addUsage(int input, int output, int total) {
        this.inputTokens = this.inputTokens + input;
        this.outputTokens = this.outputTokens + output;
        this.totalTokens = this.totalTokens + total;
    }
}
