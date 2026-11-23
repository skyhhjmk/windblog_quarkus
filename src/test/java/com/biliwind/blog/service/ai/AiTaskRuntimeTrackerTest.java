package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AiTaskRuntimeTrackerTest {

    @Test
    void tracksLifecycleAndRecordsRecentEvent() {
        AiTaskRuntimeTracker tracker = new AiTaskRuntimeTracker();
        String runId = tracker.startAudit(42L, 0, 3, Thread.currentThread());

        AiTaskRuntimeTracker.ActiveTaskSnapshot active = tracker.getActiveAudit(42L);
        assertNotNull(active);
        assertEquals("ai-audit-tasks", active.queueName());
        assertEquals("COMMENT", active.targetType());
        assertEquals(42L, active.targetId());
        assertEquals("DEQUEUED", active.stage());
        assertEquals(1, tracker.activeCountForQueue("ai-audit-tasks"));

        tracker.updateStage(runId, "CALLING_AI", "AI 模型调用中", "正在请求 AI 接口");
        active = tracker.getActiveAudit(42L);
        assertNotNull(active);
        assertEquals("CALLING_AI", active.stage());
        assertEquals("AI 模型调用中", active.stageLabel());

        tracker.finishAudit(runId, "SUCCESS", "AI 审核完成：建议通过", 320L, null);
        assertNull(tracker.getActiveAudit(42L));
        assertEquals(0, tracker.activeCountForQueue("ai-audit-tasks"));

        List<AiTaskRuntimeTracker.RecentTaskEvent> events = tracker.listRecentEvents(10);
        assertEquals(1, events.size());
        assertEquals("SUCCESS", events.get(0).status());
        assertEquals(320L, events.get(0).durationMs());
    }

    @Test
    void abortAuditCancelsAttachedFutureAndMarksAborted() {
        AiTaskRuntimeTracker tracker = new AiTaskRuntimeTracker();
        CompletableFuture<String> future = new CompletableFuture<>();
        String runId = tracker.startAudit(99L, 1, 3, Thread.currentThread());
        tracker.attachFuture(runId, future);

        assertFalse(tracker.isAborted(runId));
        boolean aborted = tracker.abortAudit(99L, "管理员手动中断");
        assertTrue(aborted);
        assertTrue(tracker.isAborted(runId));
        assertTrue(future.isCancelled());

        AiTaskRuntimeTracker.ActiveTaskSnapshot snapshot = tracker.getActiveAudit(99L);
        assertNotNull(snapshot);
        assertTrue(snapshot.aborting());
        assertEquals("ABORTING", snapshot.stage());

        assertFalse(tracker.abortAudit(9999L, "not active"));
    }
}
