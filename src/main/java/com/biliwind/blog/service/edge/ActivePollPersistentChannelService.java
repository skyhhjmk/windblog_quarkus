package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto;
import com.biliwind.blog.edge.MutinyEdgeNodeServiceGrpc;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import com.google.protobuf.ByteString;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@ApplicationScoped
public class ActivePollPersistentChannelService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivePollPersistentChannelService.class);

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    GrpcChannelFactory channelFactory;

    @Inject
    PrimaryEdgeChannelRegistry channelRegistry;

    @Inject
    EdgeNodeRegistry edgeNodeRegistry;

    @Inject
    PrimaryRoutedHttpExecutor routedHttpExecutor;

    @Scheduled(every = "30s")
    public void ensureActivePollChannels() {
        if (!nodeRoleService.isPrimaryNode()) {
            return;
        }

        List<EdgeNode> activePollNodes = EdgeNode.list(
                "connectionType = ?1 AND isEnabled = true AND isTrusted = true",
                EdgeConnectionType.ACTIVE_POLL
        );

        for (EdgeNode node : activePollNodes) {
            if (channelRegistry.hasOnlineChannel(node.nodeId)) {
                continue;
            }
            openChannel(node);
        }
    }

    @Scheduled(every = "10s")
    public void keepActivePollChannelsAlive() {
        if (!nodeRoleService.isPrimaryNode()) {
            return;
        }

        List<EdgeNode> activePollNodes = EdgeNode.list(
                "connectionType = ?1 AND isEnabled = true AND isTrusted = true",
                EdgeConnectionType.ACTIVE_POLL
        );

        for (EdgeNode node : activePollNodes) {
            if (!channelRegistry.hasOnlineChannel(node.nodeId)) {
                continue;
            }

            EdgeServiceProto.EdgeChannelMessage channelMessage = buildPrimaryStatusMessage("主节点在线");
            boolean sent = channelRegistry.sendToNode(node.nodeId, channelMessage);
            if (!sent) {
                channelRegistry.unregister(node.nodeId);
            }
        }
    }

    private void openChannel(EdgeNode node) {
        String grpcAddress = resolveGrpcAddress(node);
        if (grpcAddress == null || grpcAddress.isBlank()) {
            return;
        }

        try {
            io.grpc.ManagedChannel managedChannel = channelFactory.createChannel(grpcAddress, node.nodeId);
            MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = MutinyEdgeNodeServiceGrpc.newMutinyStub(managedChannel);

            Multi<EdgeServiceProto.EdgeChannelMessage> outboundMessages = Multi.createFrom().emitter(new Consumer<MultiEmitter<? super EdgeServiceProto.EdgeChannelMessage>>() {
                @Override
                public void accept(MultiEmitter<? super EdgeServiceProto.EdgeChannelMessage> emitter) {
                    channelRegistry.register(node.nodeId, emitter);
                    sendPrimaryStatus(emitter, node.nodeId);
                }
            });

            stub.openNodeChannel(outboundMessages).subscribe().with(new Consumer<EdgeServiceProto.EdgeChannelMessage>() {
                @Override
                public void accept(EdgeServiceProto.EdgeChannelMessage message) {
                    handleIncomingMessage(node, message);
                }
            }, new Consumer<Throwable>() {
                @Override
                public void accept(Throwable throwable) {
                    LOGGER.error("ACTIVE_POLL 持久通道断开: {}", node.nodeId, throwable);
                    channelRegistry.unregister(node.nodeId);
                    managedChannel.shutdownNow();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    channelRegistry.unregister(node.nodeId);
                    managedChannel.shutdownNow();
                }
            });
        } catch (Exception exception) {
            LOGGER.error("建立 ACTIVE_POLL 持久通道失败: {}", node.nodeId, exception);
        }
    }

    private void handleIncomingMessage(EdgeNode node, EdgeServiceProto.EdgeChannelMessage message) {
        if (message.hasHeartbeat()) {
            EdgeServiceProto.EdgeHeartbeatMessage heartbeatMessage = message.getHeartbeat();
            edgeNodeRegistry.updateNodeStatus(node.nodeId, heartbeatMessage.getMetricsMap());
            return;
        }

        if (message.hasRoutedHttpRequest()) {
            handleRoutedHttpRequest(node, message);
        }
    }

    private void handleRoutedHttpRequest(EdgeNode node, EdgeServiceProto.EdgeChannelMessage message) {
        EdgeServiceProto.RoutedHttpRequest routedRequest = message.getRoutedHttpRequest();
        RoutedHttpExchange.Request request = new RoutedHttpExchange.Request(
                routedRequest.getMethod(),
                routedRequest.getPath(),
                routedRequest.getQuery(),
                routedRequest.getHeadersMap(),
                routedRequest.getBody().toByteArray()
        );
        RoutedHttpExchange.Response response = routedHttpExecutor.execute(request);

        EdgeServiceProto.RoutedHttpResponse.Builder responseBuilder = EdgeServiceProto.RoutedHttpResponse.newBuilder()
                .setStatus(response.status());
        if (response.headers() != null) {
            responseBuilder.putAllHeaders(response.headers());
        }
        if (response.body() != null) {
            responseBuilder.setBody(ByteString.copyFrom(response.body()));
        }
        if (response.errorMessage() != null) {
            responseBuilder.setErrorMessage(response.errorMessage());
        }

        EdgeServiceProto.EdgeChannelMessage responseMessage = EdgeServiceProto.EdgeChannelMessage.newBuilder()
                .setRequestId(message.getRequestId())
                .setNodeId("main")
                .setTimestamp(System.currentTimeMillis())
                .setRoutedHttpResponse(responseBuilder.build())
                .build();
        channelRegistry.sendToNode(node.nodeId, responseMessage);
    }

    private void sendPrimaryStatus(MultiEmitter<? super EdgeServiceProto.EdgeChannelMessage> emitter, String nodeId) {
        EdgeServiceProto.EdgeChannelMessage channelMessage = buildPrimaryStatusMessage("主节点已建立主动持久通道");
        emitter.emit(channelMessage);
    }

    private EdgeServiceProto.EdgeChannelMessage buildPrimaryStatusMessage(String message) {
        EdgeServiceProto.EdgeNodeStatusMessage statusMessage = EdgeServiceProto.EdgeNodeStatusMessage.newBuilder()
                .setPrimaryOnline(true)
                .setReadOnly(false)
                .setMessage(message)
                .build();
        return EdgeServiceProto.EdgeChannelMessage.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setNodeId("main")
                .setTimestamp(System.currentTimeMillis())
                .setNodeStatus(statusMessage)
                .build();
    }

    private String resolveGrpcAddress(EdgeNode node) {
        if (node.grpcAddress != null && !node.grpcAddress.isBlank()) {
            return node.grpcAddress;
        }
        return node.address;
    }
}
