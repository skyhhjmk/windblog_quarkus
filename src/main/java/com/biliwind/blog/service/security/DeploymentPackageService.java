package com.biliwind.blog.service.security;

import com.biliwind.blog.model.EdgeNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @Inject
    @ConfigProperty(name = "user.jwt.secret", defaultValue = "windblog-user-dev-secret-change-me")
    String userJwtSecret;

    @Inject
    @ConfigProperty(name = "user.jwt.issuer", defaultValue = "windblog-user")
    String userJwtIssuer;

    @Inject
    @ConfigProperty(name = "blog.url", defaultValue = "http://localhost:8080")
    String blogUrl;

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

        int dbPort = generateRandomHighPort();
        int redisPort = generateRandomHighPort();
        int httpPort = generateRandomHighPort();

        // 数据库配置
        sb.append("EDGE_DB_USER=windblog\n");
        sb.append("EDGE_DB_PASSWORD=windblog_edge_pwd\n");
        sb.append("EDGE_DB_NAME=windblog_edge\n");
        sb.append("EDGE_DB_PORT=").append(dbPort).append("\n");
        sb.append("EDGE_DATASOURCE_URL=jdbc:postgresql://edge-db:5432/windblog_edge\n");

        // Redis 缓存配置
        sb.append("EDGE_REDIS_PORT=").append(redisPort).append("\n");
        sb.append("EDGE_REDIS_URL=redis://edge-redis:6379\n");

        // 外部暴露端口配置
        int grpcPort = 9001;
        if (node.edgeGrpcPort != null) {
            grpcPort = node.edgeGrpcPort.intValue();
        }
        sb.append("EDGE_APP_HTTP_PORT=").append(httpPort).append("\n");
        sb.append("EDGE_APP_GRPC_PORT=").append(grpcPort).append("\n");

        // 共享 JWT 密钥及主博客地址配置
        sb.append("USER_JWT_SECRET=").append(this.userJwtSecret).append("\n");
        sb.append("USER_JWT_ISSUER=").append(this.userJwtIssuer).append("\n");
        sb.append("BLOG_URL=").append(this.blogUrl).append("\n");

        return sb.toString();
    }

    private String buildDockerCompose(EdgeNode node) {
        String containerPrefix = "windblog-edge-" + node.nodeId;

        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");

        // 1. 边缘节点专用数据库容器
        sb.append("  edge-db:\n");
        sb.append("    container_name: ").append(containerPrefix).append("-db\n");
        sb.append("    image: postgres:18\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    environment:\n");
        sb.append("      POSTGRES_USER: ${EDGE_DB_USER}\n");
        sb.append("      POSTGRES_PASSWORD: ${EDGE_DB_PASSWORD}\n");
        sb.append("      POSTGRES_DB: ${EDGE_DB_NAME}\n");
        sb.append("    ports:\n");
        sb.append("      - \"${EDGE_DB_PORT}:5432\"\n");
        sb.append("    volumes:\n");
        sb.append("      - edge-db-data-").append(node.nodeId).append(":/var/lib/postgresql\n");
        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD-SHELL\", \"pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB\"]\n");
        sb.append("      interval: 10s\n");
        sb.append("      timeout: 5s\n");
        sb.append("      retries: 5\n");
        sb.append("\n");

        // 2. 边缘节点专用 Redis 容器
        sb.append("  edge-redis:\n");
        sb.append("    container_name: ").append(containerPrefix).append("-redis\n");
        sb.append("    image: redis:8-alpine\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    ports:\n");
        sb.append("      - \"${EDGE_REDIS_PORT}:6379\"\n");
        sb.append("    volumes:\n");
        sb.append("      - edge-redis-data-").append(node.nodeId).append(":/data\n");
        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD\", \"redis-cli\", \"ping\"]\n");
        sb.append("      interval: 10s\n");
        sb.append("      timeout: 5s\n");
        sb.append("      retries: 5\n");
        sb.append("\n");

        // 3. 边缘节点应用容器
        sb.append("  edge-node:\n");
        sb.append("    container_name: ").append(containerPrefix).append("-node\n");
        sb.append("    image: biliwind/windblog-edge-node:latest\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    volumes:\n");
        sb.append("      - ./certs:/deploy/certs:ro\n");
        sb.append("    env_file:\n");
        sb.append("      - .env\n");
        sb.append("    environment:\n");
        sb.append("      - QUARKUS_DATASOURCE_JDBC_URL=${EDGE_DATASOURCE_URL}\n");
        sb.append("      - QUARKUS_DATASOURCE_USERNAME=${EDGE_DB_USER}\n");
        sb.append("      - QUARKUS_DATASOURCE_PASSWORD=${EDGE_DB_PASSWORD}\n");
        sb.append("      - QUARKUS_REDIS_HOSTS=${EDGE_REDIS_URL}\n");
        sb.append("      - QUARKUS_HTTP_PORT=8081\n");
        sb.append("      - QUARKUS_GRPC_SERVER_PORT=9001\n");
        sb.append("    ports:\n");
        sb.append("      - \"${EDGE_APP_HTTP_PORT}:8081\"\n");
        sb.append("      - \"${EDGE_APP_GRPC_PORT}:9001\"\n");
        sb.append("    depends_on:\n");
        sb.append("      edge-db:\n");
        sb.append("        condition: service_healthy\n");
        sb.append("      edge-redis:\n");
        sb.append("        condition: service_healthy\n");
        sb.append("    extra_hosts:\n");
        sb.append("      - \"host.docker.internal:host-gateway\"\n");
        sb.append("\n");

        // 4. 数据卷定义
        sb.append("volumes:\n");
        sb.append("  edge-db-data-").append(node.nodeId).append(":\n");
        sb.append("  edge-redis-data-").append(node.nodeId).append(":\n");

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

    private int generateRandomHighPort() {
        java.util.Random random = new java.util.Random();
        int randomPort = random.nextInt(40000) + 20000;
        return randomPort;
    }
}