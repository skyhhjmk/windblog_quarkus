package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.*;
import com.biliwind.blog.edge.MutinyEdgeNodeServiceGrpc;
import com.google.protobuf.ByteString;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ApplicationScoped
public class EdgePersistentChannelClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgePersistentChannelClient.class);
    private final Map<String, CompletableFuture<RoutedHttpExchange.Response>> pendingWriteRequests = new ConcurrentHashMap<>();
    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    EdgeReadOnlyState readOnlyState;
    @Inject
    GrpcChannelFactory channelFactory;
    @Inject
    EdgeSyncDataApplyService syncDataApplyService;
    @Inject
    com.biliwind.blog.service.security.EdgeCertificateInstaller edgeCertificateInstaller;
    @Inject
    com.biliwind.blog.service.link.LinkProbeService linkProbeService;
    @ConfigProperty(name = "windblog.primary.grpc.host", defaultValue = "127.0.0.1")
    String primaryGrpcHost;
    @ConfigProperty(name = "windblog.primary.grpc.port", defaultValue = "9000")
    int primaryGrpcPort;
    @ConfigProperty(name = "windblog.node.region", defaultValue = "GLOBAL")
    String nodeRegion;
    @ConfigProperty(name = "windblog.edge.grpc.port", defaultValue = "9001")
    int edgeGrpcPort;
    @ConfigProperty(name = "windblog.edge.connection-type", defaultValue = "HEARTBEAT")
    String edgeConnectionType;
    private volatile MultiEmitter<? super EdgeChannelMessage> outboundEmitter;
    private volatile io.grpc.ManagedChannel managedChannel;
    private volatile boolean stopped;

    void onStart(@Observes StartupEvent event) {
        if (!nodeRoleService.isEdgeNode()) {
            return;
        }
        if (isActivePollMode()) {
            readOnlyState.markPrimaryOffline("等待主节点主动建立数据通道");
            return;
        }
        connect();
    }

    void onStop(@Observes ShutdownEvent event) {
        stopped = true;
        closeChannel();
    }

    @Scheduled(every = "10s")
    public void sendHeartbeat() {
        if (!nodeRoleService.isEdgeNode()) {
            return;
        }
        if (outboundEmitter == null) {
            if (!isActivePollMode()) {
                connect();
            }
            return;
        }

        Map<String, String> metrics = new java.util.HashMap<>();
        metrics.put("primaryOnline", String.valueOf(readOnlyState.isPrimaryOnline()));
        metrics.put("readOnly", String.valueOf(readOnlyState.isReadOnly()));
        metrics.put("readOnlyMessage", readOnlyState.getCurrentMessage());

        EdgeHeartbeatMessage heartbeatMessage = EdgeHeartbeatMessage.newBuilder()
                .setRegion(nodeRegion)
                .putAllMetrics(metrics)
                .setGrpcPort(edgeGrpcPort)
                .setLastSyncVersion(0L)
                .build();

        EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setNodeId(nodeRoleService.getNodeId())
                .setTimestamp(nowMillis())
                .setHeartbeat(heartbeatMessage)
                .build();

        sendMessage(channelMessage);
    }

    public RoutedHttpExchange.Response forwardWriteRequest(RoutedHttpExchange.Request request) {
        if (outboundEmitter == null || readOnlyState.isReadOnly()) {
            return unavailableResponse();
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<RoutedHttpExchange.Response> future = new CompletableFuture<>();
        pendingWriteRequests.put(requestId, future);

        RoutedHttpRequest.Builder routedRequestBuilder = RoutedHttpRequest.newBuilder()
                .setMethod(nullToEmpty(request.method()))
                .setPath(nullToEmpty(request.path()))
                .setQuery(nullToEmpty(request.query()));

        if (request.headers() != null) {
            routedRequestBuilder.putAllHeaders(request.headers());
        }
        if (request.body() != null) {
            routedRequestBuilder.setBody(ByteString.copyFrom(request.body()));
        }

        EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(requestId)
                .setNodeId(nodeRoleService.getNodeId())
                .setTimestamp(nowMillis())
                .setRoutedHttpRequest(routedRequestBuilder.build())
                .build();

        boolean sent = sendMessage(channelMessage);
        if (!sent) {
            pendingWriteRequests.remove(requestId);
            return unavailableResponse();
        }

        try {
            return future.get(70, TimeUnit.SECONDS);
        } catch (Exception exception) {
            pendingWriteRequests.remove(requestId);
            LOGGER.error("等待主节点写请求响应超时: {}", request.path(), exception);
            return unavailableResponse();
        }
    }

    public void registerServerSideEmitter(MultiEmitter<? super EdgeChannelMessage> emitter) {
        outboundEmitter = emitter;
        readOnlyState.markPrimaryOnline();
        sendHeartbeat();
    }

    public void clearServerSideEmitter() {
        clearServerSideEmitter(outboundEmitter);
    }

    public void clearServerSideEmitter(MultiEmitter<? super EdgeChannelMessage> emitter) {
        if (emitter == null) {
            return;
        }
        if (outboundEmitter != emitter) {
            LOGGER.debug("忽略过期主节点主动通道关闭事件");
            return;
        }
        outboundEmitter = null;
        readOnlyState.markPrimaryOffline("主节点主动通道已断开，当前从节点处于只读模式");
        failPendingRequests();
    }

    private void connect() {
        if (stopped) {
            return;
        }
        if (outboundEmitter != null) {
            return;
        }

        try {
            String grpcAddress = primaryGrpcHost + ":" + primaryGrpcPort;
            managedChannel = channelFactory.createChannel(grpcAddress, "main-node");
            MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = MutinyEdgeNodeServiceGrpc.newMutinyStub(managedChannel);

            Multi<EdgeChannelMessage> outboundMessages = Multi.createFrom().emitter(new Consumer<MultiEmitter<? super EdgeChannelMessage>>() {
                @Override
                public void accept(MultiEmitter<? super EdgeChannelMessage> emitter) {
                    outboundEmitter = emitter;
                    readOnlyState.markPrimaryOnline();
                    sendHeartbeat();
                }
            });

            stub.openNodeChannel(outboundMessages)
                    .subscribe().with(new Consumer<EdgeChannelMessage>() {
                        @Override
                        public void accept(EdgeChannelMessage message) {
                            handleIncomingMessage(message);
                        }
                    }, new Consumer<Throwable>() {
                        @Override
                        public void accept(Throwable throwable) {
                            handleChannelFailure(throwable);
                        }
                    });
        } catch (Exception exception) {
            handleChannelFailure(exception);
        }
    }

    private boolean isActivePollMode() {
        if (edgeConnectionType == null) {
            return false;
        }
        String normalizedConnectionType = edgeConnectionType.trim().toUpperCase();
        return "ACTIVE_POLL".equals(normalizedConnectionType) || "ACTIVEPOLL".equals(normalizedConnectionType);
    }

    private void handleIncomingMessage(EdgeChannelMessage message) {
        readOnlyState.markPrimaryOnline();

        if (message.hasNodeStatus()) {
            EdgeNodeStatusMessage nodeStatus = message.getNodeStatus();
            if (nodeStatus.getPrimaryOnline() && !nodeStatus.getReadOnly()) {
                readOnlyState.markPrimaryOnline();
            } else {
                readOnlyState.markPrimaryOffline(nodeStatus.getMessage());
            }
            return;
        }

        if (message.hasRoutedHttpResponse()) {
            completeWriteRequest(message);
            return;
        }

        if (message.hasSyncData()) {
            SyncDataRequest syncDataRequest = message.getSyncData();
            syncDataApplyService.apply(syncDataRequest);
            return;
        }

        if (message.hasCertificateRenewal()) {
            installRenewedCertificate(message.getCertificateRenewal());
            return;
        }

        if (message.hasCertificateActivation()) {
            LOGGER.info(
                    "新证书已在主节点生效，边缘节点即将重启并加载证书: {}",
                    message.getCertificateActivation().getRenewalId()
            );
            Quarkus.asyncExit();
            return;
        }

        if (message.hasLinkProbeRequest()) {
            handleLinkProbeRequest(message);
        }
    }

    private void handleLinkProbeRequest(EdgeChannelMessage message) {
        LinkProbeRequest request = message.getLinkProbeRequest();
        com.biliwind.blog.service.link.LinkProbeResult probeResult =
                linkProbeService.probe(request.getUrl(), request.getSiteUrl());

        LinkProbeResponse response = LinkProbeResponse.newBuilder()
                .setReachable(probeResult.reachable())
                .setStatusCode(probeResult.statusCode())
                .setLoadTimeMs(probeResult.loadTimeMs())
                .setBacklinkFound(probeResult.backlinkFound())
                .setErrorMessage(nullToEmpty(probeResult.errorMessage()))
                .build();
        EdgeChannelMessage responseMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(message.getRequestId())
                .setNodeId(nodeRoleService.getNodeId())
                .setTimestamp(nowMillis())
                .setLinkProbeResponse(response)
                .build();
        sendMessage(responseMessage);
    }

    private void installRenewedCertificate(CertificateRenewalMessage renewalMessage) {
        CertificateRenewalResult.Builder resultBuilder = CertificateRenewalResult.newBuilder()
                .setRenewalId(renewalMessage.getRenewalId());
        try {
            edgeCertificateInstaller.install(
                    renewalMessage.getCertificatePem(),
                    renewalMessage.getPrivateKeyPem(),
                    renewalMessage.getCaCertificatePem()
            );
            resultBuilder.setSuccess(true);
            resultBuilder.setMessage("新证书文件已安装");
            LOGGER.info(
                    "边缘节点新证书文件已安装: renewalId={}, serial={}",
                    renewalMessage.getRenewalId(),
                    renewalMessage.getSerialNumber()
            );
        } catch (Exception exception) {
            resultBuilder.setSuccess(false);
            resultBuilder.setMessage(exception.getMessage());
            LOGGER.error("安装边缘节点新证书失败: {}", renewalMessage.getRenewalId(), exception);
        }

        EdgeChannelMessage resultMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(renewalMessage.getRenewalId())
                .setNodeId(nodeRoleService.getNodeId())
                .setTimestamp(nowMillis())
                .setCertificateRenewalResult(resultBuilder.build())
                .build();
        sendMessage(resultMessage);
    }

    private void completeWriteRequest(EdgeChannelMessage message) {
        CompletableFuture<RoutedHttpExchange.Response> future = pendingWriteRequests.remove(message.getRequestId());
        if (future == null) {
            return;
        }

        RoutedHttpResponse routedResponse = message.getRoutedHttpResponse();
        RoutedHttpExchange.Response response = new RoutedHttpExchange.Response(
                routedResponse.getStatus(),
                routedResponse.getHeadersMap(),
                routedResponse.getBody().toByteArray(),
                routedResponse.getErrorMessage()
        );
        future.complete(response);
    }

    private boolean sendMessage(EdgeChannelMessage message) {
        MultiEmitter<? super EdgeChannelMessage> emitter = outboundEmitter;
        if (emitter == null) {
            return false;
        }

        try {
            emitter.emit(message);
            return true;
        } catch (Exception exception) {
            handleChannelFailure(exception);
            return false;
        }
    }

    private void handleChannelFailure(Throwable throwable) {
        outboundEmitter = null;
        closeChannel();
        readOnlyState.markPrimaryOffline("主节点连接不可用，当前从节点处于只读模式");
        failPendingRequests();
        if (throwable != null) {
            LOGGER.error("从节点持久通道断开", throwable);
        }
    }

    private void closeChannel() {
        io.grpc.ManagedChannel channel = managedChannel;
        managedChannel = null;
        if (channel == null) {
            return;
        }
        try {
            channel.shutdownNow();
        } catch (Exception exception) {
            LOGGER.debug("关闭从节点持久通道失败", exception);
        }
    }

    private void failPendingRequests() {
        RoutedHttpExchange.Response response = unavailableResponse();
        for (CompletableFuture<RoutedHttpExchange.Response> future : pendingWriteRequests.values()) {
            future.complete(response);
        }
        pendingWriteRequests.clear();
    }

    private RoutedHttpExchange.Response unavailableResponse() {
        byte[] body = ("{\"success\":false,\"message\":\"" + readOnlyState.getCurrentMessage() + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new RoutedHttpExchange.Response(503, Map.of("Content-Type", "application/json"), body, readOnlyState.getCurrentMessage());
    }

    private String nullToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }

    private long nowMillis() {
        return OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
