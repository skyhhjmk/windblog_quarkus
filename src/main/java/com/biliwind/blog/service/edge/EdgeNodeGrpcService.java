package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeNodeService;
import com.biliwind.blog.edge.EdgeServiceProto.*;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import com.biliwind.blog.service.security.CertificateRenewalService;
import com.biliwind.blog.service.security.EdgeCertificateInstaller;
import com.biliwind.blog.service.PostAccessService;
import com.biliwind.blog.service.storage.StorageClass;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@GrpcService
@Singleton
@Blocking
public class EdgeNodeGrpcService implements EdgeNodeService {
    private static final Logger log = LoggerFactory.getLogger(EdgeNodeGrpcService.class);

    @Inject
    EdgeNodeRegistry registry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    PrimaryEdgeChannelRegistry primaryEdgeChannelRegistry;

    @Inject
    PrimaryRoutedHttpExecutor primaryRoutedHttpExecutor;

    @Inject
    EdgeSyncDataApplyService syncDataApplyService;

    @Inject
    EdgePersistentChannelClient edgePersistentChannelClient;

    @Inject
    CertificateRenewalService certificateRenewalService;

    @Inject
    EdgeCertificateInstaller edgeCertificateInstaller;

    @Inject
    PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.service.link.DistributedLinkProbeService distributedLinkProbeService;

    private StorageService getStorageService() {
        return io.quarkus.arc.Arc.container().instance(StorageService.class).get();
    }

    @Override
    public Multi<EdgeChannelMessage> openNodeChannel(Multi<EdgeChannelMessage> request) {
        AtomicReference<String> nodeIdReference = new AtomicReference<>();
        AtomicReference<MultiEmitter<? super EdgeChannelMessage>> emitterReference = new AtomicReference<>();

        Multi<EdgeChannelMessage> outboundMessages = Multi.createFrom().emitter(new Consumer<MultiEmitter<? super EdgeChannelMessage>>() {
            @Override
            public void accept(MultiEmitter<? super EdgeChannelMessage> emitter) {
                emitterReference.set(emitter);
                if (nodeRoleService.isEdgeNode()) {
                    edgePersistentChannelClient.registerServerSideEmitter(emitter);
                }
            }
        });

        request.subscribe().with(new Consumer<EdgeChannelMessage>() {
            @Override
            public void accept(EdgeChannelMessage message) {
                handleChannelMessage(message, nodeIdReference, emitterReference);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) {
                String nodeId = nodeIdReference.get();
                MultiEmitter<? super EdgeChannelMessage> emitter = emitterReference.get();
                if (nodeId != null) {
                    primaryEdgeChannelRegistry.unregister(nodeId, emitter);
                }
                if (nodeRoleService.isEdgeNode()) {
                    edgePersistentChannelClient.clearServerSideEmitter(emitter);
                }
                log.error("边缘节点持久通道输入流异常", throwable);
            }
        }, new Runnable() {
            @Override
            public void run() {
                String nodeId = nodeIdReference.get();
                MultiEmitter<? super EdgeChannelMessage> emitter = emitterReference.get();
                if (nodeId != null) {
                    primaryEdgeChannelRegistry.unregister(nodeId, emitter);
                }
                if (nodeRoleService.isEdgeNode()) {
                    edgePersistentChannelClient.clearServerSideEmitter(emitter);
                }
            }
        });

        return outboundMessages;
    }

    private void handleChannelMessage(EdgeChannelMessage message,
                                      AtomicReference<String> nodeIdReference,
                                      AtomicReference<MultiEmitter<? super EdgeChannelMessage>> emitterReference) {
        String authenticatedNodeId = com.biliwind.blog.service.security.GrpcMtlsInterceptor.getAuthenticatedNodeId();
        if (authenticatedNodeId != null && !authenticatedNodeId.equals(message.getNodeId())) {
            log.warn("持久通道 nodeId 与 mTLS 认证身份不一致: request={}, cert={}", message.getNodeId(), authenticatedNodeId);
            return;
        }

        String nodeId = message.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        if (nodeIdReference.get() == null) {
            nodeIdReference.set(nodeId);
            MultiEmitter<? super EdgeChannelMessage> emitter = emitterReference.get();
            if (emitter != null && nodeRoleService.isPrimaryNode()) {
                primaryEdgeChannelRegistry.register(nodeId, emitter);
                sendNodeStatus(emitter, nodeId, true, false, "主节点在线");
            }
        }

        if (message.hasHeartbeat()) {
            EdgeHeartbeatMessage heartbeatMessage = message.getHeartbeat();
            registry.updateHeartbeat(nodeId, heartbeatMessage.getMetricsMap(), heartbeatMessage.getGrpcPort());
            return;
        }

        if (message.hasRoutedHttpRequest()) {
            handleRoutedHttpRequest(message, emitterReference.get());
            return;
        }

        if (message.hasSyncData()) {
            syncDataApplyService.apply(message.getSyncData());
            return;
        }

        if (message.hasCertificateRenewalResult()) {
            certificateRenewalService.completeRenewal(nodeId, message.getCertificateRenewalResult());
            return;
        }

        if (message.hasCertificateRenewal()) {
            installRenewedCertificate(message.getCertificateRenewal(), emitterReference.get(), nodeId);
            return;
        }

        if (message.hasCertificateActivation()) {
            log.info(
                    "新证书已在主节点生效，边缘节点即将重启并加载证书: {}",
                    message.getCertificateActivation().getRenewalId()
            );
            io.quarkus.runtime.Quarkus.asyncExit();
            return;
        }

        if (message.hasLinkProbeResponse()) {
            distributedLinkProbeService.complete(message.getRequestId(), message.getLinkProbeResponse());
        }
    }

    private void installRenewedCertificate(
            CertificateRenewalMessage renewalMessage,
            MultiEmitter<? super EdgeChannelMessage> emitter,
            String nodeId
    ) {
        if (emitter == null) {
            return;
        }

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
        } catch (Exception exception) {
            resultBuilder.setSuccess(false);
            resultBuilder.setMessage(exception.getMessage());
            log.error("安装边缘节点新证书失败: {}", renewalMessage.getRenewalId(), exception);
        }

        EdgeChannelMessage resultMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(renewalMessage.getRenewalId())
                .setNodeId(nodeId)
                .setTimestamp(System.currentTimeMillis())
                .setCertificateRenewalResult(resultBuilder.build())
                .build();
        emitter.emit(resultMessage);
    }

    private void handleRoutedHttpRequest(EdgeChannelMessage message,
                                         MultiEmitter<? super EdgeChannelMessage> emitter) {
        if (emitter == null) {
            return;
        }

        RoutedHttpRequest routedRequest = message.getRoutedHttpRequest();
        RoutedHttpExchange.Request request = new RoutedHttpExchange.Request(
                routedRequest.getMethod(),
                routedRequest.getPath(),
                routedRequest.getQuery(),
                routedRequest.getHeadersMap(),
                routedRequest.getBody().toByteArray()
        );
        RoutedHttpExchange.Response response = primaryRoutedHttpExecutor.execute(request);

        RoutedHttpResponse.Builder responseBuilder = RoutedHttpResponse.newBuilder()
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

        EdgeChannelMessage responseMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(message.getRequestId())
                .setNodeId("main")
                .setTimestamp(System.currentTimeMillis())
                .setRoutedHttpResponse(responseBuilder.build())
                .build();

        try {
            emitter.emit(responseMessage);
        } catch (Exception exception) {
            log.error("发送写请求回源响应失败: {}", message.getRequestId(), exception);
        }
    }

    private void sendNodeStatus(MultiEmitter<? super EdgeChannelMessage> emitter,
                                String nodeId,
                                boolean primaryOnline,
                                boolean readOnly,
                                String message) {
        EdgeNodeStatusMessage nodeStatusMessage = EdgeNodeStatusMessage.newBuilder()
                .setPrimaryOnline(primaryOnline)
                .setReadOnly(readOnly)
                .setMessage(message)
                .build();
        EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setNodeId(nodeId)
                .setTimestamp(System.currentTimeMillis())
                .setNodeStatus(nodeStatusMessage)
                .build();
        emitter.emit(channelMessage);
    }

    @Override
    public Uni<HeartbeatResponse> heartbeat(HeartbeatRequest request) {
        String authenticatedNodeId = com.biliwind.blog.service.security.GrpcMtlsInterceptor.getAuthenticatedNodeId();
        if (authenticatedNodeId == null) {
            log.warn("拒绝未经 mTLS 认证的心跳请求");
            return Uni.createFrom().item(HeartbeatResponse.newBuilder()
                    .setAccepted(false)
                    .setHeartbeatIntervalSeconds(30)
                    .build());
        }

        if (!authenticatedNodeId.equals(request.getNodeId())) {
            log.warn("心跳请求中的 nodeId ({}) 与 mTLS 认证的 nodeId ({}) 不一致，拒绝",
                    request.getNodeId(), authenticatedNodeId);
            return Uni.createFrom().item(HeartbeatResponse.newBuilder()
                    .setAccepted(false)
                    .setHeartbeatIntervalSeconds(30)
                    .build());
        }

        boolean accepted = registry.updateHeartbeat(authenticatedNodeId, request.getMetricsMap(), request.getGrpcPort());

        StorageConfigResponse config = StorageConfigResponse.newBuilder().build();
        if (accepted) {
            config = getStorageConfig(ConfigRequest.newBuilder().setNodeId(authenticatedNodeId).build())
                    .await().atMost(java.time.Duration.ofSeconds(10));
        }

        return Uni.createFrom().item(HeartbeatResponse.newBuilder()
                .setAccepted(accepted)
                .setHeartbeatIntervalSeconds(30)
                .setConfig(config)
                .build());
    }

    @Override
    public Uni<StorageConfigResponse> getStorageConfig(ConfigRequest request) {
        List<StorageClassEntity> entities = StorageClassEntity.list("isEnabled = true ORDER BY priority ASC");

        List<StorageNodeConfig> configs = entities.stream().map(e -> StorageNodeConfig.newBuilder()
                .setName(e.name)
                .setIsPrimary(e.isPrimary != null && e.isPrimary)
                .setRole(e.role != null ? e.role : "backup")
                .setPriority(e.priority != null ? e.priority : 0)
                .setCdnDomain(e.cdnDomain != null ? e.cdnDomain : "")
                .setCdnEnabled(e.cdnEnabled != null && e.cdnEnabled)
                .addAllSupportedTypes(parseSupportedTypes(e.supportedTypes))
                .build()).collect(Collectors.toList());

        return Uni.createFrom().item(StorageConfigResponse.newBuilder()
                .addAllNodes(configs)
                .setConfigVersion(System.currentTimeMillis())
                .build());
    }

    private List<String> parseSupportedTypes(String supportedTypesStr) {
        List<String> result = new ArrayList<>();
        if (supportedTypesStr == null) {
            result.add("*");
            return result;
        }
        try {
            JsonNode typesNode = objectMapper.readTree(supportedTypesStr);
            if (typesNode.isArray()) {
                for (int i = 0; i < typesNode.size(); i++) {
                    result.add(typesNode.get(i).asText());
                }
            } else {
                result.add("*");
            }
        } catch (Exception e) {
            result.add("*");
        }
        return result;
    }

    @Override
    public Uni<MediaMetadataResponse> getMediaMetadata(MediaQueryRequest request) {
        Media media = Media.findById(request.getMediaId());
        if (media == null) {
            return Uni.createFrom().failure(new Exception("Media not found: " + request.getMediaId()));
        }

        MediaMetadataResponse.Builder builder = MediaMetadataResponse.newBuilder()
                .setMediaId(media.id)
                .setStorageKey(media.storageKey != null ? media.storageKey : "")
                .setMimeType(media.mimeType != null ? media.mimeType : "")
                .setVersion(media.version != null ? media.version : 1);

        if (media.storageClasses != null) {
            boolean protectedMedia = postAccessService.hasProtectedMediaReference(media.id);
            // Get all possible variants
            for (VariantType vt : VariantType.values()) {
                if (protectedMedia && (vt == VariantType.ORIGINAL || vt == VariantType.RAW)) {
                    continue;
                }
                String variantName = vt.name().toLowerCase();
                String bestUrl = getStorageService().getBestAccessUrl(media, vt);

                VariantInfo.Builder variantBuilder = VariantInfo.newBuilder();
                if (bestUrl != null) {
                    variantBuilder.setBestUrl(bestUrl);
                }

                for (Map.Entry<String, Object> providerEntry : media.storageClasses.entrySet()) {
                    String storageClassName = providerEntry.getKey();
                    if (providerEntry.getValue() instanceof Map) {
                        Map<String, Object> providerData = (Map<String, Object>) providerEntry.getValue();
                        Object variantDataObj = providerData.get(variantName);
                        if (variantDataObj instanceof Map) {
                            Map<String, Object> variantData = (Map<String, Object>) variantDataObj;
                            NodeVariantStatus.Builder statusBuilder = NodeVariantStatus.newBuilder();
                            if (variantData.get("path") != null)
                                statusBuilder.setPath(variantData.get("path").toString());
                            if (variantData.get("status") != null)
                                statusBuilder.setStatus(variantData.get("status").toString());
                            if (variantData.get("size") != null)
                                statusBuilder.setSize(((Number) variantData.get("size")).longValue());

                            variantBuilder.putNodes(storageClassName, statusBuilder.build());
                        }
                    }
                }

                if (variantBuilder.getNodesCount() > 0) {
                    builder.putVariants(variantName, variantBuilder.build());
                }
            }
        }

        return Uni.createFrom().item(builder.build());
    }

    @Override
    public Multi<DownloadChunk> downloadMedia(MediaDownloadRequest request) {
        return Uni.createFrom().item(request)
                .onItem().transformToMulti(req -> {
                    Multi<DownloadChunk> multi;
                    try {
                        StorageClass provider = getStorageService().getPrimaryProvider();
                        if (provider == null) {
                            multi = Multi.createFrom().failure(new Exception("Primary provider not found"));
                        } else {
                            Media media = Media.findById(req.getMediaId());
                            if (media == null) {
                                multi = Multi.createFrom().failure(new Exception("Media not found: " + req.getMediaId()));
                            } else {
                                boolean protectedMedia = postAccessService.hasProtectedMediaReference(media.id);
                                String requestedVariant = req.getVariantType();
                                if (protectedMedia && ("ORIGINAL".equalsIgnoreCase(requestedVariant)
                                        || "RAW".equalsIgnoreCase(requestedVariant))) {
                                    multi = Multi.createFrom().failure(
                                            new Exception("Protected original media is not available on edge nodes"));
                                    return multi;
                                }
                                String variantName = req.getVariantType().toLowerCase();
                                Object providerDataObj = media.storageClasses.get(getStorageService().getPrimaryProviderName());
                                if (!(providerDataObj instanceof Map)) {
                                    multi = Multi.createFrom().failure(new Exception("Primary provider data not found"));
                                } else {
                                    Map<String, Object> providerData = (Map<String, Object>) providerDataObj;
                                    Map<String, Object> variantData = (Map<String, Object>) providerData.get(variantName);
                                    if (variantData == null || variantData.get("path") == null) {
                                        multi = Multi.createFrom().failure(new Exception("Variant path not found"));
                                    } else {
                                        String path = variantData.get("path").toString();
                                        InputStream is = provider.download(path);

                                        multi = Multi.createFrom().emitter(emitter -> {
                                            byte[] buffer = new byte[64 * 1024]; // 64KB chunks
                                            int bytesRead;
                                            long offset = 0;
                                            try {
                                                while ((bytesRead = is.read(buffer)) != -1) {
                                                    byte[] chunkData = new byte[bytesRead];
                                                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                                                    emitter.emit(DownloadChunk.newBuilder()
                                                            .setData(com.google.protobuf.ByteString.copyFrom(chunkData))
                                                            .setOffset(offset)
                                                            .setIsLast(false)
                                                            .build());
                                                    offset += bytesRead;
                                                }
                                                emitter.emit(DownloadChunk.newBuilder()
                                                        .setIsLast(true)
                                                        .setOffset(offset)
                                                        .build());
                                                emitter.complete();
                                            } catch (Exception e) {
                                                emitter.fail(e);
                                            } finally {
                                                try {
                                                    is.close();
                                                } catch (Exception ignored) {
                                                }
                                            }
                                        });
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        multi = Multi.createFrom().failure(e);
                    }
                    return multi;
                });
    }

    @Override
    public Uni<PollResponse> poll(PollRequest request) {
        // 主节点通常不接受来自他人的轮询，除非是级联部署
        return Uni.createFrom().failure(new UnsupportedOperationException("Main node does not support being polled."));
    }

    @Override
    public Uni<SyncDataResponse> syncData(SyncDataRequest request) {
        if (nodeRoleService.isEdgeNode()) {
            syncDataApplyService.apply(request);
            return Uni.createFrom().item(SyncDataResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("同步完成")
                    .build());
        }

        return Uni.createFrom().failure(new UnsupportedOperationException("Main node does not support receiving sync data."));
    }
}
