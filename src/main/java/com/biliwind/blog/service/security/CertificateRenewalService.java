package com.biliwind.blog.service.security;

import com.biliwind.blog.edge.EdgeServiceProto.CertificateActivationMessage;
import com.biliwind.blog.edge.EdgeServiceProto.CertificateRenewalMessage;
import com.biliwind.blog.edge.EdgeServiceProto.CertificateRenewalResult;
import com.biliwind.blog.edge.EdgeServiceProto.EdgeChannelMessage;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.service.edge.PrimaryEdgeChannelRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CertificateRenewalService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateRenewalService.class);
    private static final int RENEWAL_TIMEOUT_SECONDS = 20;
    private final Map<String, PendingRenewal> pendingRenewals =
            new ConcurrentHashMap<>();

    @Inject
    CertificateService certificateService;

    @Inject
    PrimaryEdgeChannelRegistry primaryEdgeChannelRegistry;

    public EdgeNode renew(String nodeId) throws Exception {
        EdgeNode currentNode = findNode(nodeId);
        if (currentNode == null) {
            throw new jakarta.ws.rs.NotFoundException("节点不存在");
        }
        if (!primaryEdgeChannelRegistry.hasOnlineChannel(nodeId)) {
            throw conflict("节点持久通道不在线，无法安全下发新证书");
        }

        CertificateService.GeneratedCertificate generatedCertificate =
                certificateService.generateNodeCertificate(nodeId, 72);
        String renewalId = UUID.randomUUID().toString();
        CompletableFuture<CertificateRenewalResult> renewalResultFuture = new CompletableFuture<>();
        pendingRenewals.put(renewalId, new PendingRenewal(nodeId, renewalResultFuture));

        try {
            sendRenewal(nodeId, renewalId, generatedCertificate);
            CertificateRenewalResult renewalResult =
                    renewalResultFuture.get(RENEWAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!renewalResult.getSuccess()) {
                throw conflict("边缘节点安装新证书失败: " + renewalResult.getMessage());
            }

            EdgeNode renewedNode = saveRenewedCertificate(nodeId, generatedCertificate);
            sendActivation(nodeId, renewalId);
            LOGGER.info(
                    "边缘节点 {} 证书续签成功，新证书序列号: {}，过期时间: {}",
                    nodeId,
                    generatedCertificate.serialNumber(),
                    generatedCertificate.expiry()
            );
            return renewedNode;
        } finally {
            pendingRenewals.remove(renewalId);
        }
    }

    public void completeRenewal(String nodeId, CertificateRenewalResult renewalResult) {
        PendingRenewal pendingRenewal =
                pendingRenewals.get(renewalResult.getRenewalId());
        if (pendingRenewal == null) {
            LOGGER.warn(
                    "收到无法匹配的证书续签结果: nodeId={}, renewalId={}",
                    nodeId,
                    renewalResult.getRenewalId()
            );
            return;
        }
        if (!pendingRenewal.nodeId.equals(nodeId)) {
            LOGGER.warn(
                    "拒绝节点不匹配的证书续签结果: expectedNodeId={}, actualNodeId={}, renewalId={}",
                    pendingRenewal.nodeId,
                    nodeId,
                    renewalResult.getRenewalId()
            );
            return;
        }
        pendingRenewal.resultFuture.complete(renewalResult);
    }

    private EdgeNode findNode(String nodeId) {
        return QuarkusTransaction.requiringNew().call(new java.util.concurrent.Callable<EdgeNode>() {
            @Override
            public EdgeNode call() {
                return EdgeNode.findByNodeId(nodeId);
            }
        });
    }

    private void sendRenewal(
            String nodeId,
            String renewalId,
            CertificateService.GeneratedCertificate generatedCertificate
    ) {
        CertificateRenewalMessage renewalMessage = CertificateRenewalMessage.newBuilder()
                .setRenewalId(renewalId)
                .setCertificatePem(generatedCertificate.certificatePem())
                .setPrivateKeyPem(generatedCertificate.privateKeyPem())
                .setCaCertificatePem(generatedCertificate.caCertificatePem())
                .setSerialNumber(generatedCertificate.serialNumber())
                .setExpiryEpochSeconds(generatedCertificate.expiry().toEpochSecond())
                .build();
        EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(renewalId)
                .setNodeId(nodeId)
                .setTimestamp(nowMillis())
                .setCertificateRenewal(renewalMessage)
                .build();

        boolean sent = primaryEdgeChannelRegistry.sendToNode(nodeId, channelMessage);
        if (!sent) {
            throw conflict("节点持久通道已断开，无法下发新证书");
        }
    }

    private EdgeNode saveRenewedCertificate(
            String nodeId,
            CertificateService.GeneratedCertificate generatedCertificate
    ) {
        return QuarkusTransaction.requiringNew().call(new java.util.concurrent.Callable<EdgeNode>() {
            @Override
            public EdgeNode call() {
                EdgeNode node = EdgeNode.findByNodeId(nodeId);
                if (node == null) {
                    throw new jakarta.ws.rs.NotFoundException("节点不存在");
                }

                node.certificateBackupSerial = node.certificateSerial;
                node.certificateBackupExpiry = node.certificateExpiry;
                node.certificateSerial = generatedCertificate.serialNumber();
                node.certificateExpiry = generatedCertificate.expiry();
                node.certificateRevoked = false;
                node.persist();
                return node;
            }
        });
    }

    private void sendActivation(String nodeId, String renewalId) {
        CertificateActivationMessage activationMessage = CertificateActivationMessage.newBuilder()
                .setRenewalId(renewalId)
                .build();
        EdgeChannelMessage channelMessage = EdgeChannelMessage.newBuilder()
                .setRequestId(renewalId)
                .setNodeId(nodeId)
                .setTimestamp(nowMillis())
                .setCertificateActivation(activationMessage)
                .build();
        boolean sent = primaryEdgeChannelRegistry.sendToNode(nodeId, channelMessage);
        if (!sent) {
            LOGGER.warn("新证书已入库，但未能通知节点 {} 重启；节点下次重启时会加载新证书", nodeId);
        }
    }

    private long nowMillis() {
        return OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private WebApplicationException conflict(String message) {
        return new WebApplicationException(message, 409);
    }

    private static class PendingRenewal {
        private final String nodeId;
        private final CompletableFuture<CertificateRenewalResult> resultFuture;

        private PendingRenewal(
                String nodeId,
                CompletableFuture<CertificateRenewalResult> resultFuture
        ) {
            this.nodeId = nodeId;
            this.resultFuture = resultFuture;
        }
    }
}
