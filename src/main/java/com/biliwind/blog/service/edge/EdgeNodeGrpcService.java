package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeNodeService;
import com.biliwind.blog.edge.EdgeServiceProto.*;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageProviderEntity;
import com.biliwind.blog.service.storage.StorageProvider;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private StorageService getStorageService() {
        return io.quarkus.arc.Arc.container().instance(StorageService.class).get();
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

        boolean accepted = registry.updateHeartbeat(authenticatedNodeId, request.getMetricsMap());

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
        List<StorageProviderEntity> entities = StorageProviderEntity.list("isEnabled = true ORDER BY priority ASC");

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

        if (media.storageProviders != null) {
            // Get all possible variants
            for (VariantType vt : VariantType.values()) {
                String variantName = vt.name().toLowerCase();
                String bestUrl = getStorageService().getBestAccessUrl(media, vt);

                VariantInfo.Builder variantBuilder = VariantInfo.newBuilder();
                if (bestUrl != null) {
                    variantBuilder.setBestUrl(bestUrl);
                }

                for (Map.Entry<String, Object> providerEntry : media.storageProviders.entrySet()) {
                    String providerName = providerEntry.getKey();
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

                            variantBuilder.putNodes(providerName, statusBuilder.build());
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
                        StorageProvider provider = getStorageService().getPrimaryProvider();
                        if (provider == null) {
                            multi = Multi.createFrom().failure(new Exception("Primary provider not found"));
                        } else {
                            Media media = Media.findById(req.getMediaId());
                            if (media == null) {
                                multi = Multi.createFrom().failure(new Exception("Media not found: " + req.getMediaId()));
                            } else {
                                String variantName = req.getVariantType().toLowerCase();
                                Object providerDataObj = media.storageProviders.get(getStorageService().getPrimaryProviderName());
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
        return Uni.createFrom().failure(new UnsupportedOperationException("Main node does not support receiving sync data."));
    }
}
