package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiTopicPoolRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端请求在事务中调用 {@code runs.start}，异步任务必须脱离该事务执行。
 * 如果 ManagedExecutor 把请求线程的 JTA 事务传播到工作线程，事务提交后
 * 工作线程会拿着已结束的事务继续执行，Panache 抛出
 * "Cannot use the EntityManager/Session because neither a transaction nor a CDI
 * request context is active"，运行记录以 FAILED 结束。
 */
@QuarkusTest
class AiTopicRunAsyncTest {

    @Inject
    LocalAiTopicService topicService;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void failActiveRuns() {
        QuarkusTransaction.requiringNew().run(() ->
                AiTopicPoolRun.update("status = 'FAILED' where status in ('QUEUED', 'RUNNING')"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsTopicsWhenRunStartsInsideCallerTransaction() throws Exception {
        AiTopicDiscoveryService discovery = new AiTopicDiscoveryService() {
            @Override
            public List<Map<String, Object>> discover(Long providerId, List<Seed> seeds,
                                                      int maxTopics, String language) {
                try {
                    // 让调用方事务先提交，模拟真实的管理端请求返回。
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return List.of(Map.of(
                        "title", "异步事务隔离测试 " + UUID.randomUUID(),
                        "recommendation", "WRITE",
                        "rationale", "regression test"));
            }
        };
        QuarkusMock.installMockForType(discovery, AiTopicDiscoveryService.class);

        Map<String, Object> payload = Map.of(
                "seeds", List.of(Map.of("id", 1, "name", "test", "query", "quarkus", "language", "zh-CN")),
                "maxTopics", 1);

        Map<String, Object> created = QuarkusTransaction.requiringNew().call(() ->
                (Map<String, Object>) topicService.dispatch("runs.start",
                        objectMapper.valueToTree(payload), "test-actor", "test-trace"));
        long runId = ((Number) created.get("id")).longValue();

        String status = awaitTerminalStatus(runId);
        String error = QuarkusTransaction.requiringNew().call(() -> {
            AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
            return run == null ? "" : run.errorMessage;
        });
        assertEquals("SUCCEEDED", status, "异步话题导入失败: " + error);
        assertTrue(QuarkusTransaction.requiringNew().call(() -> {
            AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
            return run != null && run.topicCount >= 1;
        }), "运行应导入至少一个话题");
    }

    private String awaitTerminalStatus(long runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String status = QuarkusTransaction.requiringNew().call(() -> {
                AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
                return run == null ? null : run.status;
            });
            if (status != null && !"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return "TIMEOUT";
    }
}
