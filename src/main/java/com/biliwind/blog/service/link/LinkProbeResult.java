package com.biliwind.blog.service.link;

public record LinkProbeResult(
        boolean reachable,
        int statusCode,
        int loadTimeMs,
        boolean backlinkFound,
        String errorMessage
) {
}
