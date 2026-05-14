package com.biliwind.blog.edge;

import com.biliwind.blog.edge.EdgeServiceProto.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
@io.smallrye.common.annotation.Blocking
public class EdgeGrpcServer implements EdgeNodeService {
    private static final Logger log = LoggerFactory.getLogger(EdgeGrpcServer.class);

    @Inject
    EdgeGrpcClient edgeGrpcClient;

    @Inject
    EdgeDataSyncService dataSyncService;

    @Override
    public Uni<PollResponse> poll(PollRequest request) {
        log.info("Received poll request from main node: {}", request.getMainNodeId());

        // 更新存储配置
        if (request.hasConfig()) {
            edgeGrpcClient.updateConfig(request.getConfig());
        }

        // 返回当前节点信息
        return Uni.createFrom().item(PollResponse.newBuilder()
                .setNodeId(edgeGrpcClient.getNodeId())
                .setRegion(edgeGrpcClient.getRegion())
                .putAllMetrics(edgeGrpcClient.getMetrics())
                .build());
    }

    @Override
    public Uni<SyncDataResponse> syncData(SyncDataRequest request) {
        log.info("Received sync data request: {} {}", request.getEntityType(), request.getAction());
        return Uni.createFrom().item(dataSyncService.processSync(request));
    }

    @Override
    public Uni<HeartbeatResponse> heartbeat(HeartbeatRequest request) {
        return Uni.createFrom().failure(new UnsupportedOperationException("Edge node does not support receiving heartbeats."));
    }

    @Override
    public Uni<StorageConfigResponse> getStorageConfig(ConfigRequest request) {
        return Uni.createFrom().item(edgeGrpcClient.getCurrentConfig());
    }

    @Override
    public Uni<MediaMetadataResponse> getMediaMetadata(MediaQueryRequest request) {
        // 边缘节点通常不直接提供元数据给他人，除非是级联部署
        return Uni.createFrom().failure(new UnsupportedOperationException("Edge node does not support direct metadata query."));
    }

    @Override
    public io.smallrye.mutiny.Multi<DownloadChunk> downloadMedia(MediaDownloadRequest request) {
        return io.smallrye.mutiny.Multi.createFrom().failure(new UnsupportedOperationException("Edge node does not support direct download fallback."));
    }
}
