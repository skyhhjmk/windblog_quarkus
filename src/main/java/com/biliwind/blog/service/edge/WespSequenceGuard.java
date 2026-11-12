package com.biliwind.blog.service.edge;

import com.biliwind.blog.model.WespSyncSequenceFrontier;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Enforces the WESP applied-sequence frontier: an operation is only applied
 * when it is the next contiguous sequence for its sender incarnation, so a
 * missing sequence is never skipped.
 */
@ApplicationScoped
public class WespSequenceGuard {

    public enum Verdict {
        APPLY,
        WAITING_DEPENDENCY,
        REPLAY
    }

    public Verdict check(String nodeId, String incarnation, String datasetId, long seq) {
        WespSyncSequenceFrontier frontier = find(nodeId, incarnation, datasetId);
        if (frontier == null) {
            return Verdict.APPLY;
        }
        if (seq <= frontier.appliedSeq) {
            return Verdict.REPLAY;
        }
        if (seq > frontier.appliedSeq + 1) {
            return Verdict.WAITING_DEPENDENCY;
        }
        return Verdict.APPLY;
    }

    public long appliedThrough(String nodeId, String incarnation, String datasetId) {
        WespSyncSequenceFrontier frontier = find(nodeId, incarnation, datasetId);
        return frontier == null ? 0L : frontier.appliedSeq;
    }

    public void advance(String nodeId, String incarnation, String datasetId, long seq) {
        WespSyncSequenceFrontier frontier = find(nodeId, incarnation, datasetId);
        if (frontier == null) {
            frontier = new WespSyncSequenceFrontier();
            frontier.id = new WespSyncSequenceFrontier.Key(nodeId, incarnation, datasetId);
            frontier.appliedSeq = seq;
            frontier.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
            frontier.persist();
            return;
        }
        if (seq > frontier.appliedSeq) {
            frontier.appliedSeq = seq;
            frontier.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    private WespSyncSequenceFrontier find(String nodeId, String incarnation, String datasetId) {
        return WespSyncSequenceFrontier.findById(
                new WespSyncSequenceFrontier.Key(nodeId, incarnation, datasetId));
    }
}
