package com.biliwind.blog.service.edge;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * gRPC 通道工厂，负责创建支持 mTLS 的安全通道。
 */
@ApplicationScoped
public class GrpcChannelFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcChannelFactory.class);
    private static final String CERT_DIR = "certs/ca";

    /**
     * 创建支持 mTLS 的 gRPC 通道。
     * 如果证书文件不存在，则回退到明文连接（用于兼容旧节点或初始引导）。
     */
    public ManagedChannel createChannel(String grpcAddress) {
        try {
            File caFile = new File(CERT_DIR + "/ca.crt");
            File clientCertFile = new File(CERT_DIR + "/client.crt");
            File clientKeyFile = new File(CERT_DIR + "/client.key");

            if (!caFile.exists() || !clientCertFile.exists() || !clientKeyFile.exists()) {
                LOGGER.warn("TLS 证书文件缺失，回退到明文模式: {}", grpcAddress);
                return io.grpc.ManagedChannelBuilder.forTarget(grpcAddress).usePlaintext().build();
            }

            // 构建 SSL 上下文 (mTLS)
            SslContext sslContext = GrpcSslContexts.forClient()
                    .trustManager(caFile)
                    .keyManager(clientCertFile, clientKeyFile)
                    .build();

            LOGGER.info("正在为 {} 创建加密的 gRPC 通道 (mTLS)", grpcAddress);
            return NettyChannelBuilder.forTarget(grpcAddress)
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            LOGGER.error("构建加密 gRPC 通道失败: {}，回退到明文模式", e.getMessage());
            return io.grpc.ManagedChannelBuilder.forTarget(grpcAddress).usePlaintext().build();
        }
    }
}
