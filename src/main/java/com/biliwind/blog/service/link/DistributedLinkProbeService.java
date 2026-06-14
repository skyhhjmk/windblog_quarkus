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

    @Inject
    PrimaryEdgeChannelRegistry channelRegistry;

    public List<NodeLinkProbeResult> probeOnlineEdgeNodes(String url, String siteUrl) {
        List<String> nodeIds = channelRegistry.listOnlineNodeIds();
        List<PendingNodeProbe> pendingNodeProbes = new ArrayList<>();

        for (String nodeId : nodeIds) {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<LinkProbeResult> future = new CompletableFuture<>();
            pendingRequests.put(requestId, future);

            LinkProbeRequest probeRequest = LinkProbeRequest.newBuilder()
                    .setUrl(url)
                    .setSiteUrl(siteUrl)
                    .build();
            EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                    .setRequestId(requestId)
                    .setNodeId("main")
                    .setTimestamp(System.currentTimeMillis())
                    .setLinkProbeRequest(probeRequest)
                    .build();

            boolean sent = channelRegistry.sendToNode(nodeId, channelMessage);
            if (sent) {
                pendingNodeProbes.add(new PendingNodeProbe(nodeId, requestId, future));
            } else {
                pendingRequests.remove(requestId);
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
        LinkProbeResult result = new LinkProbeResult(
                response.getReachable(),
                response.getStatusCode(),
                response.getLoadTimeMs(),
                response.getBacklinkFound(),
                response.getErrorMessage()
        );
        future.complete(result);
    }

    private LinkProbeResult awaitResult(PendingNodeProbe pendingNodeProbe) {
        try {
            return pendingNodeProbe.future.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            pendingRequests.remove(pendingNodeProbe.requestId);
            return new LinkProbeResult(false, 0, 15000, false, "节点探测超时或通道断开");
        }
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

        private PendingNodeProbe(
                String nodeId,
                String requestId,
                CompletableFuture<LinkProbeResult> future
        ) {
            this.nodeId = nodeId;
            this.requestId = requestId;
            this.future = future;
        }
    }
}
