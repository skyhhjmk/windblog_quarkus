package com.biliwind.blog.service.link;

public record NodeLinkProbeResult(
        String nodeId,
        String nodeName,
        LinkProbeResult probeResult
) {
}
