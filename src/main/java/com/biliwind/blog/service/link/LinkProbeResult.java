package com.biliwind.blog.service.link;

public record LinkProbeResult(
        boolean reachable,
        int statusCode,
        int loadTimeMs,
        boolean backlinkFound,
        String errorMessage,
        LinkProbeEvidence evidence
) {
    public LinkProbeResult(boolean reachable, int statusCode, int loadTimeMs, boolean backlinkFound, String errorMessage) {
        this(reachable, statusCode, loadTimeMs, backlinkFound, errorMessage, LinkProbeEvidence.unavailable(""));
    }
}
