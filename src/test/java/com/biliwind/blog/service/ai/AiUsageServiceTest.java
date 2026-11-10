package com.biliwind.blog.service.ai;

import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiUsageSummaryDto;
import com.biliwind.blog.model.AiProviderConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AiUsageServiceTest {

    @Inject
    AiUsageService aiUsageService;

    @Test
    void shouldRecordAndSummarizeAiUsage() {
        AiProviderConfig config = new AiProviderConfig();
        config.name = "test-provider";
        config.provider = "OPENAI";
        config.model = "gpt-4o-mini";

        AiResult successResult = new AiResult();
        successResult.addUsage(100, 50, 150);

        long start = System.currentTimeMillis();
        aiUsageService.record(config, "translate", successResult, 120, null);

        AiResult errorResult = new AiResult();
        aiUsageService.record(config, "summarize", errorResult, 50, new RuntimeException("Rate limit"));

        AiUsageSummaryDto summary = aiUsageService.getSummary(7);
        assertNotNull(summary);
        assertTrue(summary.totalRequests() >= 2);
        assertTrue(summary.successRequests() >= 1);
        assertTrue(summary.failedRequests() >= 1);
        assertTrue(summary.promptTokens() >= 100);
        assertTrue(summary.completionTokens() >= 50);
        assertTrue(summary.totalTokens() >= 150);

        assertTrue(summary.features().stream().anyMatch(f -> "translate".equals(f.feature()) && f.requests() >= 1));
        assertTrue(summary.features().stream().anyMatch(f -> "summarize".equals(f.feature()) && f.requests() >= 1));
        assertTrue(summary.providers().stream().anyMatch(p -> "test-provider".equals(p.providerName())));
    }
}
