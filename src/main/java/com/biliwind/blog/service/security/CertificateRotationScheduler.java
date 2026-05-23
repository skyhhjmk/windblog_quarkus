package com.biliwind.blog.service.security;

import com.biliwind.blog.model.EdgeNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 证书自动轮换任务。
 * 检查即将过期的节点证书并生成新的序列号/到期时间（实际推送逻辑待补充）。
 */
@ApplicationScoped
public class CertificateRotationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateRotationScheduler.class);

    @Inject
    CertificateService certificateService;

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    @Scheduled(every = "12h")
    @Transactional
    public void rotateCertificates() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        LOGGER.info("正在执行边缘节点证书过期检查...");

        // 查找即将过期（剩余不足 6 小时）且未被吊销的节点
        OffsetDateTime threshold = OffsetDateTime.now().plusHours(6);
        List<EdgeNode> nodesToRotate = EdgeNode.find("certificateRevoked = false AND certificateExpiry < ?1", threshold).list();

        if (nodesToRotate.isEmpty()) {
            LOGGER.info("没有需要轮换证书的节点");
            return;
        }

        for (EdgeNode node : nodesToRotate) {
            try {
                LOGGER.info("节点 {} 的证书即将过期 ({})，正在轮换证书", node.nodeId, node.certificateExpiry);

                CertificateService.GeneratedCertificate primary = certificateService.generateNodeCertificate(node.nodeId, 24);
                CertificateService.GeneratedCertificate backup = certificateService.generateNodeCertificate(node.nodeId, 72);

                node.certificateSerial = primary.serialNumber();
                node.certificateExpiry = primary.expiry();
                node.certificateBackupSerial = backup.serialNumber();
                node.certificateBackupExpiry = backup.expiry();
                node.persist();

                LOGGER.info("节点 {} 证书轮换成功，新主证书序列号: {}，新备用证书序列号: {}",
                        node.nodeId, node.certificateSerial, node.certificateBackupSerial);
            } catch (Exception e) {
                LOGGER.error("节点 {} 证书轮换失败: {}", node.nodeId, e.getMessage(), e);
            }
        }
    }
}
