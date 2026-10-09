package com.biliwind.blog.service.link;

import com.biliwind.blog.edge.EdgeServiceProto.EdgeChannelMessage;
import com.biliwind.blog.edge.EdgeServiceProto.LinkProbeRequest;
import com.biliwind.blog.edge.EdgeServiceProto.LinkProbeResponse;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.service.edge.PrimaryEdgeChannelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DistributedLinkProbeService {

    private final Map<String, CompletableFuture<LinkProbeResult>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, ProbeContext> pendingContexts = new ConcurrentHashMap<>();

    @Inject
    PrimaryEdgeChannelRegistry channelRegistry;

    public List<NodeLinkProbeResult> probeOnlineEdgeNodes(String url, List<String> siteUrls, String siteName,
                                                          String configuredKeywords, String targetName) {
        List<String> nodeIds = channelRegistry.listOnlineNodeIds();
        List<PendingNodeProbe> pendingNodeProbes = new ArrayList<>();
        List<String> expectedKeywords = expectedKeywords(siteName, configuredKeywords);
        List<String> safeSiteUrls = siteUrls == null ? List.of() : List.copyOf(siteUrls);
        String primarySiteUrl = safeSiteUrls.isEmpty() ? "" : safeSiteUrls.get(0);

        for (String nodeId : nodeIds) {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<LinkProbeResult> future = new CompletableFuture<>();
            pendingRequests.put(requestId, future);
            pendingContexts.put(requestId,
                    new ProbeContext(url, targetName, primarySiteUrl, safeSiteUrls, siteName, expectedKeywords));

            LinkProbeRequest probeRequest = LinkProbeRequest.newBuilder()
                    .setUrl(url)
                    .setSiteUrl(primarySiteUrl)
                    .setTargetName(targetName == null ? "" : targetName)
                    .setSiteName(siteName == null ? "" : siteName)
                    .addAllMonitoringKeywords(configuredKeywords(configuredKeywords))
                    .addAllSiteUrls(safeSiteUrls)
                    .build();
            EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                    .setRequestId(requestId)
                    .setNodeId("main")
                    .setTimestamp(System.currentTimeMillis())
                    .setLinkProbeRequest(probeRequest)
                    .build();

            boolean sent = channelRegistry.sendToNode(nodeId, channelMessage);
            if (sent) {
                pendingNodeProbes.add(new PendingNodeProbe(nodeId, requestId, future,
                        pendingContexts.get(requestId)));
            } else {
                pendingRequests.remove(requestId);
                pendingContexts.remove(requestId);
            }
        }

        List<NodeLinkProbeResult> results = new ArrayList<>();
        for (PendingNodeProbe pendingNodeProbe : pendingNodeProbes) {
            LinkProbeResult result = awaitResult(pendingNodeProbe);
            results.add(new NodeLinkProbeResult(
                    pendingNodeProbe.nodeId,
                    resolveNodeName(pendingNodeProbe.nodeId),
                    result
            ));
        }
        return results;
    }

    public void complete(String requestId, LinkProbeResponse response) {
        CompletableFuture<LinkProbeResult> future = pendingRequests.remove(requestId);
        if (future == null) {
            return;
        }
        ProbeContext context = pendingContexts.remove(requestId);
        String checkedUrl = response.getCheckedUrl().isBlank()
                ? context == null ? "" : context.checkedUrl()
                : response.getCheckedUrl();
        String targetName = response.getTargetName().isBlank()
                ? context == null ? "" : context.targetName()
                : response.getTargetName();
        String siteUrl = response.getSiteUrl().isBlank()
                ? context == null ? "" : context.siteUrl()
                : response.getSiteUrl();
        String siteName = response.getSiteName().isBlank()
                ? context == null ? "" : context.siteName()
                : response.getSiteName();
        List<String> expectedKeywords = response.getExpectedKeywordsCount() == 0
                ? context == null ? List.of() : context.expectedKeywords()
                : response.getExpectedKeywordsList();
        List<String> expectedSiteUrls = response.getExpectedSiteUrlsCount() == 0
                ? context == null ? List.of() : context.expectedSiteUrls()
                : response.getExpectedSiteUrlsList();
        LinkProbeEvidence evidence = new LinkProbeEvidence(
                checkedUrl,
                targetName,
                siteUrl,
                siteName,
                expectedKeywords,
                response.getMatchedKeywordsList(),
                response.getMatchedBacklinkUrlsList(),
                response.getMatchedAnchorTextsList(),
                response.getKeywordFraudDetected(),
                response.getFraudReasonsList(),
                response.getDomParseErrorCount(),
                response.getEvidenceSupported(),
                expectedSiteUrls);
        LinkProbeResult result = new LinkProbeResult(
                response.getReachable(),
                response.getStatusCode(),
                response.getLoadTimeMs(),
                response.getBacklinkFound(),
                response.getErrorMessage(),
                evidence
        );
        future.complete(result);
    }

    private LinkProbeResult awaitResult(PendingNodeProbe pendingNodeProbe) {
        try {
            return pendingNodeProbe.future.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            pendingRequests.remove(pendingNodeProbe.requestId);
            pendingContexts.remove(pendingNodeProbe.requestId);
            ProbeContext context = pendingNodeProbe.context;
            LinkProbeEvidence evidence = context == null ? LinkProbeEvidence.unavailable("")
                    : new LinkProbeEvidence(context.checkedUrl(), context.targetName(), context.siteUrl(),
                            context.siteName(), context.expectedKeywords(), List.of(), List.of(), List.of(),
                            false, List.of(), 0, false, context.expectedSiteUrls());
            return new LinkProbeResult(false, 0, 15000, false, "节点探测超时或通道断开", evidence);
        }
    }

    private List<String> expectedKeywords(String siteName, String configuredKeywords) {
        List<String> result = new ArrayList<>();
        if (siteName != null && !siteName.isBlank()) {
            result.add(siteName.trim());
        }
        for (String keyword : configuredKeywords(configuredKeywords)) {
            if (result.size() >= 20) {
                break;
            }
            if (!result.contains(keyword)) {
                result.add(keyword);
            }
        }
        return List.copyOf(result);
    }

    private List<String> configuredKeywords(String configuredKeywords) {
        if (configuredKeywords == null || configuredKeywords.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(configuredKeywords.split("[,，;；\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.length() > 100 ? value.substring(0, 100) : value)
                .distinct()
                .limit(20)
                .toList();
    }

    private String resolveNodeName(String nodeId) {
        EdgeNode edgeNode = EdgeNode.findByNodeId(nodeId);
        if (edgeNode == null || edgeNode.name == null || edgeNode.name.isBlank()) {
            return nodeId;
        }
        return edgeNode.name;
    }

    private static class PendingNodeProbe {
        private final String nodeId;
        private final String requestId;
        private final CompletableFuture<LinkProbeResult> future;
        private final ProbeContext context;

        private PendingNodeProbe(
                String nodeId,
                String requestId,
                CompletableFuture<LinkProbeResult> future,
                ProbeContext context
        ) {
            this.nodeId = nodeId;
            this.requestId = requestId;
            this.future = future;
            this.context = context;
        }
    }

    private record ProbeContext(String checkedUrl, String targetName, String siteUrl, List<String> expectedSiteUrls,
                                String siteName,
                                List<String> expectedKeywords) {
    }
}
