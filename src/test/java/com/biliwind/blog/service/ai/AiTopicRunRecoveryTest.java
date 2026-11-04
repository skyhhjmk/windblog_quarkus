package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiArticleDraftRun;
import com.biliwind.blog.model.AiTopicPoolRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AiTopicRunRecoveryTest {

    @Inject
    AiTopicRunService topicRunService;

    @Inject
    AiArticleDraftRunService draftRunService;

    @Test
    @Transactional
    void failsOrphanedQueuedTopicRun() {
        AiTopicPoolRun run = new AiTopicPoolRun();
        run.seedCount = 1;
        run.createdAt = OffsetDateTime.now().minusMinutes(30);
        run.persist();

        topicRunService.recoverStaleRuns();

        assertEquals("FAILED", AiTopicPoolRun.<AiTopicPoolRun>findById(run.id).status);
    }

    @Test
    @Transactional
    void keepsFreshQueuedTopicRun() {
        AiTopicPoolRun run = new AiTopicPoolRun();
        run.seedCount = 1;
        run.createdAt = OffsetDateTime.now();
        run.persist();

        topicRunService.recoverStaleRuns();

        assertEquals("QUEUED", AiTopicPoolRun.<AiTopicPoolRun>findById(run.id).status);
    }

    @Test
    @Transactional
    void failsTimedOutRunningTopicRun() {
        AiTopicPoolRun run = new AiTopicPoolRun();
        run.seedCount = 1;
        run.createdAt = OffsetDateTime.now().minusHours(2);
        run.startedAt = OffsetDateTime.now().minusHours(1);
        run.status = "RUNNING";
        run.persist();

        topicRunService.recoverStaleRuns();

        assertEquals("FAILED", AiTopicPoolRun.<AiTopicPoolRun>findById(run.id).status);
    }

    @Test
    @Transactional
    void failsOrphanedDraftRun() {
        AiArticleDraftRun run = new AiArticleDraftRun();
        run.topicId = 1L;
        run.createdAt = OffsetDateTime.now().minusMinutes(30);
        run.persist();

        draftRunService.recoverStaleRuns();

        assertEquals("FAILED", AiArticleDraftRun.<AiArticleDraftRun>findById(run.id).status);
    }

    @Test
    @Transactional
    void keepsFreshDraftRun() {
        AiArticleDraftRun run = new AiArticleDraftRun();
        run.topicId = 1L;
        run.createdAt = OffsetDateTime.now();
        run.persist();

        draftRunService.recoverStaleRuns();

        assertEquals("QUEUED", AiArticleDraftRun.<AiArticleDraftRun>findById(run.id).status);
    }
}
