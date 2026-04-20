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
     * 是否安全 (用于审核功能)
     */
    public Boolean isSafe;

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
