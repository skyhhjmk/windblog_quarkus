package com.biliwind.blog.service.edge;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Optional;

/**
 * gRPC 通道工厂，负责创建支持 mTLS 的安全通道。
 */
@ApplicationScoped
public class GrpcChannelFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcChannelFactory.class);

    @ConfigProperty(name = "windblog.grpc.client.ca-certificate")
    Optional<String> configuredCaCertificatePath;

    @ConfigProperty(name = "windblog.grpc.client.certificate")
    Optional<String> configuredClientCertificatePath;

    @ConfigProperty(name = "windblog.grpc.client.key")
    Optional<String> configuredClientKeyPath;

    @ConfigProperty(name = "windblog.grpc.client.rewrite-local-target", defaultValue = "false")
    boolean rewriteLocalTarget;

    @ConfigProperty(name = "windblog.grpc.client.allow-plaintext-fallback", defaultValue = "false")
    boolean allowPlaintextFallback;

    /**
     * 创建支持 mTLS 的 gRPC 通道。
     * 证书缺失时直接失败，禁止通过明文连接绕过 mTLS。
     */
    public ManagedChannel createChannel(String grpcAddress, String nodeId) {
        try {
            String channelTarget = resolveChannelTarget(grpcAddress);
            File caFile = findFirstExistingFile(
                    configuredCaCertificatePath,
                    "certs/ca/ca.crt",
                    "certs/ca.crt"
            );

            if (caFile == null) {
                if (!allowPlaintextFallback) {
                    throw new IllegalStateException("gRPC CA 证书文件缺失，已禁止明文回退: " + channelTarget);
                }
                LOGGER.warn("CA 证书文件缺失，回退到明文模式: {}", channelTarget);
                return io.grpc.ManagedChannelBuilder.forTarget(channelTarget).usePlaintext().build();
            }

            SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient().trustManager(caFile);
            File clientCertificateFile = findFirstExistingFile(
                    configuredClientCertificatePath,
                    "certs/ca/server.crt",
                    "certs/server.crt"
            );
            File clientKeyFile = findFirstExistingFile(
                    configuredClientKeyPath,
                    "certs/ca/server.key",
                    "certs/server.key"
            );
            if (clientCertificateFile != null && clientKeyFile != null) {
                sslContextBuilder.keyManager(clientCertificateFile, clientKeyFile);
            } else {
                if (!allowPlaintextFallback) {
                    throw new IllegalStateException("gRPC 客户端证书或私钥缺失，无法建立 mTLS: " + channelTarget);
                }
                LOGGER.warn("gRPC 客户端证书或私钥缺失，将只校验服务端证书: {}", channelTarget);
            }
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
        if (!rewriteLocalTarget) {
            return grpcAddress;
        }

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

    private File findFirstExistingFile(Optional<String> configuredPath, String firstDefaultPath, String secondDefaultPath) {
        if (configuredPath != null && configuredPath.isPresent()) {
            String configuredPathText = configuredPath.get();
            if (configuredPathText == null || configuredPathText.isBlank()) {
                return findFirstExistingDefaultFile(firstDefaultPath, secondDefaultPath);
            }

            File configuredFile = new File(configuredPathText.trim());
            if (configuredFile.exists()) {
                return configuredFile;
            }
            LOGGER.warn("配置的证书文件不存在: {}", configuredFile.getPath());
        }

        return findFirstExistingDefaultFile(firstDefaultPath, secondDefaultPath);
    }

    private File findFirstExistingDefaultFile(String firstDefaultPath, String secondDefaultPath) {
        File firstDefaultFile = new File(firstDefaultPath);
        if (firstDefaultFile.exists()) {
            return firstDefaultFile;
        }

        File secondDefaultFile = new File(secondDefaultPath);
        if (secondDefaultFile.exists()) {
            return secondDefaultFile;
        }

        return null;
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
