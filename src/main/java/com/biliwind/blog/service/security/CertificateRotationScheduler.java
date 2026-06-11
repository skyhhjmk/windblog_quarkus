package com.biliwind.blog.service.security;

import com.biliwind.blog.model.EdgeNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 证书自动轮换任务。
 * 检查即将过期的节点证书，并通过已认证的持久通道完成续签。
 */
@ApplicationScoped
public class CertificateRotationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateRotationScheduler.class);

    @Inject
    CertificateRenewalService certificateRenewalService;

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    @Scheduled(every = "1h")
    public void rotateCertificates() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        LOGGER.info("正在执行边缘节点证书过期检查...");

        OffsetDateTime threshold = OffsetDateTime.now().plusHours(12);
        List<String> nodeIdsToRenew = findNodeIdsToRenew(threshold);

        if (nodeIdsToRenew.isEmpty()) {
            LOGGER.info("没有需要轮换证书的节点");
            return;
        }

        for (String nodeId : nodeIdsToRenew) {
            try {
                LOGGER.info("节点 {} 的证书将在 12 小时内过期，正在续签", nodeId);
                certificateRenewalService.renew(nodeId);
            } catch (Exception exception) {
                LOGGER.error("节点 {} 证书续签失败: {}", nodeId, exception.getMessage(), exception);
            }
        }
    }

    private List<String> findNodeIdsToRenew(OffsetDateTime threshold) {
        return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(new java.util.concurrent.Callable<List<String>>() {
                    @Override
                    public List<String> call() {
                        List<EdgeNode> nodes = EdgeNode.find(
                                "certificateRevoked = false AND certificateExpiry IS NOT NULL AND certificateExpiry < ?1",
                                threshold
                        ).list();
                        List<String> nodeIds = new java.util.ArrayList<>();
                        for (EdgeNode node : nodes) {
                            nodeIds.add(node.nodeId);
                        }
                        return nodeIds;
                    }
                });
    }
}
