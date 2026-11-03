package com.biliwind.blog.service.ai;

public class AiConcurrencyLimitException extends RuntimeException {

    private final String providerName;
    private final int concurrencyLimit;

    public AiConcurrencyLimitException(String providerName, int concurrencyLimit) {
        super("AI 提供商 " + providerName + " 并发达到上限 (" + concurrencyLimit + ")");
        this.providerName = providerName;
        this.concurrencyLimit = concurrencyLimit;
    }

    public String getProviderName() {
        return providerName;
    }

    public int getConcurrencyLimit() {
        return concurrencyLimit;
    }
}
