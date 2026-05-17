package com.biliwind.blog.service.security;

import com.biliwind.blog.model.EdgeNode;
import io.grpc.*;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;

/**
 * gRPC 服务端拦截器，用于在 TLS 握手之后，进一步校验客户端证书在数据库中的状态。
 * 检查项包括：证书序列号是否存在、是否已被吊销、是否已过期。
 */
@ApplicationScoped
@GlobalInterceptor
public class GrpcMtlsInterceptor implements ServerInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcMtlsInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 获取 SSL 会话信息
        SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (sslSession == null) {
            // 非加密连接，根据系统安全策略决定是否放行。这里选择继续，让底层校验决定。
            return next.startCall(call, headers);
        }

        try {
            // 获取客户端证书链
            X509Certificate[] chain = (X509Certificate[]) sslSession.getPeerCertificates();
            if (chain == null || chain.length == 0) {
                LOGGER.warn("客户端未提供证书，拒绝请求");
                call.close(Status.UNAUTHENTICATED.withDescription("Client certificate required"), new Metadata());
                return new ServerCall.Listener<ReqT>() {
                };
            }

            // 获取终端证书（第一个）
            X509Certificate clientCert = chain[0];
            String serialNumber = clientCert.getSerialNumber().toString();

            // 业务校验：检查数据库状态
            if (!validateCertificateInDb(serialNumber)) {
                LOGGER.warn("拒绝无效证书连接: Serial={}", serialNumber);
                call.close(Status.PERMISSION_DENIED.withDescription("Certificate is revoked or expired"), new Metadata());
                return new ServerCall.Listener<ReqT>() {
                };
            }

            LOGGER.debug("证书校验通过: Serial={}", serialNumber);

        } catch (SSLPeerUnverifiedException e) {
            LOGGER.error("SSL 握手验证失败: {}", e.getMessage());
            call.close(Status.UNAUTHENTICATED.withDescription("SSL session unverified"), new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        } catch (Exception e) {
            LOGGER.error("证书校验过程异常: {}", e.getMessage());
            call.close(Status.INTERNAL.withDescription("Certificate validation error"), new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        }

        return next.startCall(call, headers);
    }

    /**
     * 在数据库中查找并验证证书状态。
     */
    @Transactional
    protected boolean validateCertificateInDb(String serialNumber) {
        // 查找持有该证书序列号的边缘节点（主证书或备用证书）
        EdgeNode node = EdgeNode.find("certificateSerial = ?1 OR certificateBackupSerial = ?1", serialNumber).firstResult();

        if (node == null) {
            // 如果找不到匹配的序列号，说明该证书不是由本系统签发给节点的
            return false;
        }

        // 检查是否已被手动吊销
        if (node.certificateRevoked) {
            LOGGER.warn("拒绝连接：证书已吊销 - 节点: {}, Serial: {}", node.nodeId, serialNumber);
            return false;
        }

        // 检查有效期（虽然 TLS 已查过，这里再查一遍数据库记录）
        OffsetDateTime now = OffsetDateTime.now();
        boolean isPrimary = serialNumber.equals(node.certificateSerial);
        OffsetDateTime expiry = isPrimary ? node.certificateExpiry : node.certificateBackupExpiry;

        if (expiry != null && expiry.isBefore(now)) {
            LOGGER.warn("拒绝连接：证书已过期 - 节点: {}, Serial: {}, Expiry: {}", node.nodeId, serialNumber, expiry);
            return false;
        }

        // 如果是首次成功连接或重新连接，确保标记为已受信任
        if (!node.isTrusted) {
            node.isTrusted = true;
            node.persist();
            LOGGER.info("节点 {} 已通过 mTLS 认证并标记为受信任", node.nodeId);
        }

        return true;
    }
}
