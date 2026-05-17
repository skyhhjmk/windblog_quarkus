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

    @Scheduled(every = "12h")
    @Transactional
    public void rotateCertificates() {
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
                LOGGER.info("节点 {} 的证书即将过期 ({})，正在生成新证书标记", node.nodeId, node.certificateExpiry);

                // 重新签发证书（主证书 24h）
                // 注意：在 Active Pull 模式下，主节点作为 Client，需要更新它持有的 Client 证书。
                // 如果边缘节点作为 Server，它需要定期向主节点拉取或由主节点推送新证书。
                // 这里暂时只更新数据库中的有效期和序列号，作为可信验证的基础。
                CertificateService.GeneratedCertificate primary = certificateService.generateNodeCertificate(node.nodeId, 24);

                node.certificateSerial = primary.serialNumber();
                node.certificateExpiry = primary.expiry();
                node.persist();

                LOGGER.info("节点 {} 证书轮换成功，新序列号: {}", node.nodeId, node.certificateSerial);
            } catch (Exception e) {
                LOGGER.error("节点 {} 证书轮换失败: {}", node.nodeId, e.getMessage(), e);
            }
        }
    }
}
