package com.biliwind.blog.service.edge;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

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
    public ManagedChannel createChannel(String grpcAddress, String nodeId) {
        try {
            String channelTarget = resolveChannelTarget(grpcAddress);
            File caFile = new File(CERT_DIR + "/ca.crt");

            if (!caFile.exists()) {
                LOGGER.warn("CA 证书文件缺失，回退到明文模式: {}", channelTarget);
                return io.grpc.ManagedChannelBuilder.forTarget(channelTarget).usePlaintext().build();
            }

            SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient().trustManager(caFile);
            LOGGER.info("正在为 {} 创建加密的 gRPC 通道", channelTarget);

            SslContext sslContext = sslContextBuilder.build();

            return NettyChannelBuilder.forTarget(channelTarget)
                    .overrideAuthority(nodeId)
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            LOGGER.error("构建加密 gRPC 通道失败: {}", grpcAddress, e);
            throw new IllegalStateException("构建加密 gRPC 通道失败: " + grpcAddress, e);
        }
    }

    private String resolveChannelTarget(String grpcAddress) {
        HostAndPort hostAndPort = parseHostAndPort(grpcAddress);
        if (hostAndPort == null) {
            return grpcAddress;
        }

        if (!isLocalAddress(hostAndPort.host)) {
            return grpcAddress;
        }

        String loopbackTarget = "127.0.0.1:" + hostAndPort.port;
        if (!loopbackTarget.equals(grpcAddress)) {
            LOGGER.info("gRPC 目标地址 {} 是本机地址，改用 {} 连接", grpcAddress, loopbackTarget);
        }
        return loopbackTarget;
    }

    private HostAndPort parseHostAndPort(String grpcAddress) {
        if (grpcAddress == null || grpcAddress.isEmpty()) {
            return null;
        }

        int lastColonIndex = grpcAddress.lastIndexOf(':');
        if (lastColonIndex <= 0) {
            return null;
        }

        if (lastColonIndex == grpcAddress.length() - 1) {
            return null;
        }

        String host = grpcAddress.substring(0, lastColonIndex);
        String port = grpcAddress.substring(lastColonIndex + 1);
        if (host.isEmpty() || port.isEmpty()) {
            return null;
        }

        return new HostAndPort(host, port);
    }

    private boolean isLocalAddress(String host) {
        try {
            InetAddress targetAddress = InetAddress.getByName(host);
            if (targetAddress.isAnyLocalAddress() || targetAddress.isLoopbackAddress()) {
                return true;
            }

            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
                while (interfaceAddresses.hasMoreElements()) {
                    InetAddress interfaceAddress = interfaceAddresses.nextElement();
                    if (interfaceAddress.equals(targetAddress)) {
                        return true;
                    }
                }
            }
        } catch (Exception exception) {
            LOGGER.debug("无法判断 gRPC 目标地址是否为本机地址: {}", host, exception);
        }
        return false;
    }

    private static class HostAndPort {
        final String host;
        final String port;

        HostAndPort(String host, String port) {
            this.host = host;
            this.port = port;
        }
    }
}
