package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The single implementation of durable AI run states. Topic discovery and
 * article drafting store different payloads, but their status transitions,
 * queued/running timeouts and stale-run recovery are one policy selected by
 * {@link AiRunKind}.
 */
@ApplicationScoped
public class AiRunLifecycle {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String FAILED = "FAILED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String DRAFT_CREATED = "DRAFT_CREATED";

    public List<String> activeStatuses() {
        return List.of(QUEUED, RUNNING);
    }

    /** Marks every run whose worker died or whose provider call never returned. */
    public <R extends AiRunRecord> List<R> recoverStale(List<R> runs, AiRunKind kind,
                                                        OffsetDateTime now) {
        List<R> recovered = new ArrayList<>();
        for (R run : runs) {
            boolean queued = QUEUED.equals(run.runStatus());
            OffsetDateTime anchor = run.runStartedAt() == null ? run.runCreatedAt() : run.runStartedAt();
            if (anchor == null) anchor = now;
            Duration timeout = queued ? kind.queuedTimeout() : kind.runningTimeout();
            if (anchor.plus(timeout).isAfter(now)) {
                continue;
            }
            run.markRunFailed(queued ? kind.queuedFailureMessage() : kind.runningFailureMessage(), now);
            run.persistRun();
            recovered.add(run);
        }
        return recovered;
    }
}
