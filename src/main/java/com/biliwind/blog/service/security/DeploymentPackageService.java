package com.biliwind.blog.service.security;

import com.biliwind.blog.model.EdgeNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class DeploymentPackageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentPackageService.class);

    @Inject
    CertificateService certificateService;

    /**
     * 为边缘节点生成完整的部署 ZIP 包。
     * ZIP 解压后对应目录结构：
     * <pre>
     * windblog-edge-{nodeId}/
     *   certs/
     *     server.crt
     *     server.key
     *     backup.crt
     *     backup.key
     *     ca.crt
     *   .env
     *   docker-compose.yml
     *   README.txt
     * </pre>
     */
    public byte[] buildDeploymentZip(EdgeNode node) throws Exception {
        CertificateService.GeneratedCertificate primary = certificateService.generateNodeCertificate(node.nodeId, 24);
        CertificateService.GeneratedCertificate backup = certificateService.generateNodeCertificate(node.nodeId, 72);

        node.certificateSerial = primary.serialNumber();
        node.certificateExpiry = primary.expiry();
        node.certificateBackupSerial = backup.serialNumber();
        node.certificateBackupExpiry = backup.expiry();
        node.certificateRevoked = false;

        String baseDir = "windblog-edge-" + node.nodeId;

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream);

        addTextEntry(zipOutputStream, baseDir + "/certs/server.crt", primary.certificatePem());
        addTextEntry(zipOutputStream, baseDir + "/certs/server.key", primary.privateKeyPem());
        addTextEntry(zipOutputStream, baseDir + "/certs/backup.crt", backup.certificatePem());
        addTextEntry(zipOutputStream, baseDir + "/certs/backup.key", backup.privateKeyPem());
        addTextEntry(zipOutputStream, baseDir + "/certs/ca.crt", primary.caCertificatePem());

        String envContent = buildEnvContent(node);
        addTextEntry(zipOutputStream, baseDir + "/.env", envContent);

        String dockerComposeContent = buildDockerCompose(node);
        addTextEntry(zipOutputStream, baseDir + "/docker-compose.yml", dockerComposeContent);

        String readmeContent = buildReadme(node);
        addTextEntry(zipOutputStream, baseDir + "/README.txt", readmeContent);

        zipOutputStream.close();
        byte[] zipBytes = byteArrayOutputStream.toByteArray();

        LOGGER.info("已为节点 {} 生成部署 ZIP 包 ({} bytes)", node.nodeId, zipBytes.length);
        return zipBytes;
    }

    private String buildEnvContent(EdgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("EDGE_NODE_ID=").append(node.nodeId).append("\n");
        sb.append("EDGE_NODE_REGION=").append(node.region.getCode()).append("\n");
        String mainHost = node.grpcAddress;
        if (mainHost == null || mainHost.isEmpty()) {
            mainHost = "host.docker.internal";
        }
        // 提取主机名，不带端口
        String hostOnly = mainHost;
        int colonIndex = mainHost.lastIndexOf(":");
        if (colonIndex > 0) {
            hostOnly = mainHost.substring(0, colonIndex);
        }
        sb.append("MAIN_NODE_GRPC_HOST=").append(hostOnly).append("\n");
        sb.append("MAIN_NODE_GRPC_PORT=9000\n");
        sb.append("EDGE_CERT_PATH=/deploy/certs/server.crt\n");
        sb.append("EDGE_KEY_PATH=/deploy/certs/server.key\n");
        sb.append("CA_CERT_PATH=/deploy/certs/ca.crt\n");
        sb.append("EDGE_BACKUP_CERT_PATH=/deploy/certs/backup.crt\n");
        sb.append("EDGE_BACKUP_KEY_PATH=/deploy/certs/backup.key\n");
        sb.append("EDGE_CONNECTION_TYPE=").append(node.connectionType.name()).append("\n");
        return sb.toString();
    }

    private String buildDockerCompose(EdgeNode node) {
        int port = node.edgeGrpcPort != null ? node.edgeGrpcPort : 9001;
        String containerName = "windblog-edge-" + node.nodeId;

        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");
        sb.append("  edge-node:\n");
        sb.append("    image: biliwind/windblog-edge-node:latest\n");
        sb.append("    container_name: ").append(containerName).append("\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    volumes:\n");
        sb.append("      - ./certs:/deploy/certs:ro\n");
        sb.append("    env_file:\n");
        sb.append("      - .env\n");
        sb.append("    ports:\n");
        sb.append("      - \"").append(port).append(":").append(port).append("\"\n");
        sb.append("    extra_hosts:\n");
        sb.append("      - \"host.docker.internal:host-gateway\"\n");
        return sb.toString();
    }

    private String buildReadme(EdgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("WindBlog 边缘节点部署包\n");
        sb.append("========================================\n");
        sb.append("\n");
        sb.append("节点 ID: ").append(node.nodeId).append("\n");
        sb.append("节点名称: ").append(node.name).append("\n");
        sb.append("连接模式: ").append(node.connectionType.name()).append("\n");
        sb.append("区域: ").append(node.region.getCode()).append("\n");
        sb.append("gRPC 端口: ").append(node.edgeGrpcPort != null ? node.edgeGrpcPort : 9001).append("\n");
        sb.append("\n");
        sb.append("主证书有效期: 24 小时\n");
        sb.append("备用证书有效期: 72 小时\n");
        sb.append("（证书到期前主节点会自动签发新证书，需要重新部署更新）\n");
        sb.append("\n");
        sb.append("========================================\n");
        sb.append("部署步骤\n");
        sb.append("========================================\n");
        sb.append("\n");
        sb.append("1. 将此 ZIP 解压到目标服务器的一个目录下\n");
        sb.append("\n");
        sb.append("2. 进入解压后的目录:\n");
        sb.append("   cd windblog-edge-").append(node.nodeId).append("\n");
        sb.append("\n");
        sb.append("3. 确保 Docker 和 Docker Compose 已安装\n");
        sb.append("\n");
        sb.append("4. 如果主节点不在 host.docker.internal 上,\n");
        sb.append("   请编辑 .env 文件修改 MAIN_NODE_GRPC_HOST 为实际主节点地址\n");
        sb.append("\n");
        sb.append("5. 启动服务:\n");
        sb.append("   docker compose up -d\n");
        sb.append("\n");
        sb.append("6. 查看日志确认正常:\n");
        sb.append("   docker compose logs -f\n");
        sb.append("\n");
        sb.append("========================================\n");
        return sb.toString();
    }

    private void addTextEntry(ZipOutputStream zipOutputStream, String entryPath, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        zipOutputStream.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zipOutputStream.write(bytes);
        zipOutputStream.closeEntry();
    }
}