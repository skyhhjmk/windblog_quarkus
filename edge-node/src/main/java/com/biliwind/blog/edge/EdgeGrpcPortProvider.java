package com.biliwind.blog.edge;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class EdgeGrpcPortProvider {

    private static final Logger log = LoggerFactory.getLogger(EdgeGrpcPortProvider.class);
    @ConfigProperty(name = "quarkus.grpc.server.port")
    int configuredPort;
    private volatile int grpcPort = -1;

    void onStart(@Observes StartupEvent event) {
        if (configuredPort > 0) {
            grpcPort = configuredPort;
            log.info("从节点 gRPC 端口: {}", grpcPort);
        } else if (configuredPort == 0) {
            grpcPort = 0;
            log.warn("从节点使用随机 gRPC 端口（port=0），心跳中将上报 grpcPort=0。"
                    + "主节点将无法自动更新端口，建议在 .env.edge 中设置 QUARKUS_GRPC_SERVER_PORT 为固定值。");
        }
    }

    public int getGrpcPort() {
        return grpcPort;
    }
}
