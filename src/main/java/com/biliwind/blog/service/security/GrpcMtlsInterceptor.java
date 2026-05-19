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
 * 验证通过后，将已验证的 nodeId 写入 gRPC Context，供后续业务层使用。
 */
@ApplicationScoped
@GlobalInterceptor
public class GrpcMtlsInterceptor implements ServerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcMtlsInterceptor.class);

    private static final Context.Key<String> AUTHENTICATED_NODE_ID_KEY = Context.key("authenticatedNodeId");

    /**
     * 从当前 gRPC Context 中获取已通过 mTLS 认证的节点 ID。
     * 如果当前请求没有经过 mTLS 认证，返回 null。
     */
    public static String getAuthenticatedNodeId() {
        return AUTHENTICATED_NODE_ID_KEY.get();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (sslSession == null) {
            LOGGER.warn("非 TLS 连接：无 SSL 会话，拒绝请求");
            call.close(Status.UNAUTHENTICATED.withDescription("TLS required"), new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        }

        try {
            X509Certificate[] chain = (X509Certificate[]) sslSession.getPeerCertificates();
            if (chain == null || chain.length == 0) {
                LOGGER.warn("客户端未提供证书，拒绝请求");
                call.close(Status.UNAUTHENTICATED.withDescription("Client certificate required"), new Metadata());
                return new ServerCall.Listener<ReqT>() {
                };
            }

            X509Certificate clientCert = chain[0];
            String serialNumber = clientCert.getSerialNumber().toString();
            String subjectCN = extractCN(clientCert.getSubjectX500Principal().getName());

            CertificateValidationResult validationResult = validateCertificateInDb(serialNumber, subjectCN);
            if (!validationResult.valid) {
                LOGGER.warn("证书校验失败: Serial={}, Reason={}", serialNumber, validationResult.reason);
                call.close(Status.PERMISSION_DENIED.withDescription(validationResult.reason), new Metadata());
                return new ServerCall.Listener<ReqT>() {
                };
            }

            LOGGER.debug("证书校验通过: NodeId={}, Serial={}", validationResult.nodeId, serialNumber);

            Context ctx = Context.current().withValue(AUTHENTICATED_NODE_ID_KEY, validationResult.nodeId);
            return Contexts.interceptCall(ctx, call, headers, next);

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
    }

    /**
     * 从 X.500 名称中提取 CN 字段的值。
     */
    private String extractCN(String principalName) {
        if (principalName == null) {
            return "";
        }
        String[] parts = principalName.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return "";
    }

    /**
     * 在数据库中查找并验证证书状态。
     */
    @Transactional
    protected CertificateValidationResult validateCertificateInDb(String serialNumber, String subjectCN) {
        EdgeNode node = EdgeNode.find(
                "certificateSerial = ?1 OR certificateBackupSerial = ?1", serialNumber
        ).firstResult();

        if (node == null) {
            return CertificateValidationResult.failed("证书序列号未在数据库中找到，节点尚未被管理员添加");
        }

        if (node.certificateRevoked) {
            return CertificateValidationResult.failed("证书已被吊销");
        }

        OffsetDateTime now = OffsetDateTime.now();
        boolean isPrimary = serialNumber.equals(node.certificateSerial);
        OffsetDateTime expiry = isPrimary ? node.certificateExpiry : node.certificateBackupExpiry;

        if (expiry != null && expiry.isBefore(now)) {
            return CertificateValidationResult.failed("证书已过期");
        }

        if (!subjectCN.isEmpty() && !subjectCN.equals(node.nodeId)) {
            return CertificateValidationResult.failed("证书 CN 与节点 ID 不匹配");
        }

        if (!node.isEnabled) {
            return CertificateValidationResult.failed("节点已被禁用");
        }

        if (!node.isTrusted) {
            node.isTrusted = true;
            node.persist();
            LOGGER.info("节点 {} 已通过 mTLS 认证并标记为受信任", node.nodeId);
        }

        return CertificateValidationResult.passed(node.nodeId);
    }

    /**
     * 证书校验结果
     */
    private static class CertificateValidationResult {
        final boolean valid;
        final String reason;
        final String nodeId;

        private CertificateValidationResult(boolean valid, String reason, String nodeId) {
            this.valid = valid;
            this.reason = reason;
            this.nodeId = nodeId;
        }

        static CertificateValidationResult passed(String nodeId) {
            return new CertificateValidationResult(true, "", nodeId);
        }

        static CertificateValidationResult failed(String reason) {
            return new CertificateValidationResult(false, reason, null);
        }
    }
}
