package com.biliwind.blog.service.security;

import com.biliwind.blog.common.helper.RsaHelper;
import com.biliwind.blog.model.EdgeNode;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class DeploymentPackageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentPackageService.class);
    private volatile SecureRandom secureRandom;
    private static final String DEFAULT_ADMIN_JWT_SECRET = "windblog-admin-dev-secret-change-me";
    private static final String DEFAULT_USER_JWT_SECRET = "windblog-user-dev-secret-change-me";
    private static final String DEFAULT_ADMIN_INIT_PASSWORD = "admin";

    @Inject
    CertificateService certificateService;

    @Inject
    RsaHelper rsaHelper;

    @Inject
    @ConfigProperty(name = "user.jwt.secret", defaultValue = "windblog-user-dev-secret-change-me")
    String userJwtSecret;

    @Inject
    @ConfigProperty(name = "user.jwt.issuer", defaultValue = "windblog-user")
    String userJwtIssuer;

    @Inject
    @ConfigProperty(name = "admin.jwt.secret", defaultValue = "windblog-admin-dev-secret-change-me")
    String adminJwtSecret;

    @Inject
    @ConfigProperty(name = "admin.jwt.issuer", defaultValue = "windblog-admin")
    String adminJwtIssuer;

    @Inject
    @ConfigProperty(name = "admin.init.password", defaultValue = DEFAULT_ADMIN_INIT_PASSWORD)
    String adminInitPassword;

    @Inject
    @ConfigProperty(name = "security.event-hash-secret", defaultValue = "windblog-dev-event-hash-secret")
    String eventHashSecret;

    @Inject
    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "http://localhost:8080")
    String blogUrl;

    @Inject
    @ConfigProperty(name = "windblog.primary.grpc.advertised-host", defaultValue = "host.docker.internal")
    String primaryGrpcAdvertisedHost;

    @Inject
    @ConfigProperty(name = "windblog.primary.grpc.advertised-port", defaultValue = "9000")
    int primaryGrpcAdvertisedPort;

    @Inject
    @ConfigProperty(
            name = "windblog.edge.deployment.image-repository",
            defaultValue = "ghcr.io/skyhhjmk/windblog_quarkus"
    )
    String edgeImageRepository;

    /**
     * 为边缘节点生成完整的部署 ZIP 包。
     * ZIP 解压后对应目录结构：
     * <pre>
     * windblog-edge-{nodeId}/
     *   certs/
     *     ca/
     *       server.crt
     *       server.key
     *       backup.crt
     *       backup.key
     *       ca.crt
     *       truststore.p12
     *   .env
     *   docker-compose.yml
     *   README.txt
     * </pre>
     */
    public byte[] buildDeploymentZip(EdgeNode node) throws Exception {
        return buildDeploymentZip(node, null, EdgeImageVariant.NATIVE_MICRO);
    }

    public byte[] buildDeploymentZip(
            EdgeNode node,
            String requestedImageReference,
            EdgeImageVariant imageVariant
    ) throws Exception {
        ensureFixedDeploymentPorts(node);
        EdgeImageVariant effectiveImageVariant = imageVariant;
        if (effectiveImageVariant == null) {
            effectiveImageVariant = EdgeImageVariant.NATIVE_MICRO;
        }
        String imageReference = resolveImageReference(requestedImageReference, effectiveImageVariant);

        CertificateService.GeneratedCertificate primary = certificateService.generateNodeCertificate(node.nodeId, 24);
        CertificateService.GeneratedCertificate backup = certificateService.generateNodeCertificate(node.nodeId, 72);
        String trustStorePassword = generateSecretHex(32);
        String edgeDatabasePassword = generateSecretHex(32);
        String edgeRedisPassword = generateSecretHex(32);

        node.certificateSerial = primary.serialNumber();
        node.certificateExpiry = primary.expiry();
        node.certificateBackupSerial = backup.serialNumber();
        node.certificateBackupExpiry = backup.expiry();
        node.certificateRevoked = false;

        String baseDir = "windblog-edge-" + node.nodeId;

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream);

        addTextEntry(zipOutputStream, baseDir + "/certs/ca/server.crt", primary.certificatePem());
        addTextEntry(zipOutputStream, baseDir + "/certs/ca/server.key", primary.privateKeyPem());
        addTextEntry(zipOutputStream, baseDir + "/certs/ca/backup.crt", backup.certificatePem());
        addTextEntry(zipOutputStream, baseDir + "/certs/ca/backup.key", backup.privateKeyPem());
        addTextEntry(zipOutputStream, baseDir + "/certs/ca/ca.crt", primary.caCertificatePem());
        addBinaryEntry(zipOutputStream, baseDir + "/certs/ca/truststore.p12",
                buildTrustStoreBytes(primary.caCertificatePem(), trustStorePassword));

        String envContent = buildEnvContent(node, imageReference, effectiveImageVariant,
                trustStorePassword, edgeDatabasePassword, edgeRedisPassword);
        addTextEntry(zipOutputStream, baseDir + "/.env", envContent);

        String dockerComposeContent = buildDockerCompose(node);
        addTextEntry(zipOutputStream, baseDir + "/docker-compose.yml", dockerComposeContent);

        String readmeContent = buildReadme(node, imageReference, effectiveImageVariant);
        addTextEntry(zipOutputStream, baseDir + "/README.txt", readmeContent);

        zipOutputStream.close();
        byte[] zipBytes = byteArrayOutputStream.toByteArray();

        LOGGER.info("已为节点 {} 生成部署 ZIP 包 ({} bytes)", node.nodeId, zipBytes.length);
        return zipBytes;
    }

    private String buildEnvContent(
            EdgeNode node,
            String imageReference,
            EdgeImageVariant imageVariant,
            String trustStorePassword,
            String edgeDatabasePassword,
            String edgeRedisPassword
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("EDGE_NODE_ID=").append(node.nodeId).append("\n");
        sb.append("EDGE_NODE_REGION=").append(node.region.getCode()).append("\n");
        sb.append("MAIN_NODE_GRPC_HOST=").append(primaryGrpcAdvertisedHost).append("\n");
        sb.append("MAIN_NODE_GRPC_PORT=").append(primaryGrpcAdvertisedPort).append("\n");

        int grpcPort = 9001;
        if (node.edgeGrpcPort != null) {
            grpcPort = node.edgeGrpcPort.intValue();
        }
        int httpPort = node.edgeHttpPort.intValue();

        sb.append("EDGE_GRPC_PORT=").append(grpcPort).append("\n");
        sb.append("EDGE_APP_GRPC_PORT=").append(grpcPort).append("\n");

        sb.append("EDGE_CONNECTION_TYPE=").append(node.connectionType.name()).append("\n");
        sb.append("WINDBLOG_NODE_ROLE=edge\n");
        sb.append("QUARKUS_PROFILE=edge\n");
        sb.append("GRPC_CLIENT_CA_CERTIFICATE=certs/ca/ca.crt\n");
        sb.append("GRPC_CLIENT_CERTIFICATE=certs/ca/server.crt\n");
        sb.append("GRPC_CLIENT_KEY=certs/ca/server.key\n");
        sb.append("GRPC_CLIENT_REWRITE_LOCAL_TARGET=false\n");
        sb.append("GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK=false\n");
        sb.append("GRPC_SERVER_CERTIFICATE=certs/ca/server.crt\n");
        sb.append("GRPC_SERVER_KEY=certs/ca/server.key\n");
        sb.append("GRPC_SERVER_TRUST_STORE=certs/ca/truststore.p12\n");
        sb.append("GRPC_SERVER_TRUST_STORE_PASSWORD=").append(trustStorePassword).append("\n");
        sb.append("GRPC_SERVER_CLIENT_AUTH=required\n");
        sb.append("COOKIE_SECURE=true\n");
        sb.append("WINDBLOG_SITE_PUBLIC_URL=").append(blogUrl).append("\n");
        sb.append("CORS_ORIGINS=").append(blogUrl).append("\n");
        sb.append("CORS_ALLOW_CREDENTIALS=false\n");
        sb.append("SECURITY_HEADERS_CSP_ENFORCE=true\n");
        sb.append("SECURITY_HEADERS_HSTS_ENABLED=true\n");
        sb.append("SWAGGER_UI_ENABLED=false\n");
        sb.append("ADMIN_INIT_ENABLED=false\n");
        sb.append("SECURITY_EVENT_HASH_SECRET=").append(eventHashSecret).append("\n");

        sb.append("EDGE_DB_USER=windblog\n");
        sb.append("EDGE_DB_PASSWORD=").append(edgeDatabasePassword).append("\n");
        sb.append("EDGE_DB_NAME=windblog_edge\n");
        sb.append("EDGE_DATASOURCE_URL=jdbc:postgresql://edge-db:5432/windblog_edge\n");

        sb.append("EDGE_REDIS_PASSWORD=").append(edgeRedisPassword).append("\n");
        sb.append("EDGE_REDIS_URL=redis://:").append(edgeRedisPassword).append("@edge-redis:6379\n");

        sb.append("EDGE_APP_HTTP_PORT=").append(httpPort).append("\n");
        sb.append("WINDBLOG_EDGE_IMAGE=").append(imageReference).append("\n");
        sb.append("WINDBLOG_EDGE_IMAGE_VARIANT=").append(imageVariant.getRequestValue()).append("\n");

        sb.append("USER_JWT_SECRET=").append(this.userJwtSecret).append("\n");
        sb.append("USER_JWT_ISSUER=").append(this.userJwtIssuer).append("\n");
        sb.append("ADMIN_JWT_SECRET=").append(this.adminJwtSecret).append("\n");
        sb.append("ADMIN_JWT_ISSUER=").append(this.adminJwtIssuer).append("\n");
        sb.append("ADMIN_INIT_PASSWORD=").append(this.adminInitPassword).append("\n");
        appendDevelopmentSecurityCompatibility(sb);
        sb.append("BLOG_URL=").append(this.blogUrl).append("\n");
        appendClusterPublicKey(sb);

        return sb.toString();
    }

    private void appendDevelopmentSecurityCompatibility(StringBuilder envContentBuilder) {
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            return;
        }
        if (!usesDefaultSecurityConfiguration()) {
            return;
        }

        envContentBuilder.append("SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD=false\n");
        LOGGER.warn("主节点正在使用开发默认安全配置，边缘部署包仅为本地联调关闭默认值启动拦截");
    }

    private boolean usesDefaultSecurityConfiguration() {
        if (DEFAULT_ADMIN_JWT_SECRET.equals(this.adminJwtSecret)) {
            return true;
        }
        if (DEFAULT_USER_JWT_SECRET.equals(this.userJwtSecret)) {
            return true;
        }
        return DEFAULT_ADMIN_INIT_PASSWORD.equals(this.adminInitPassword);
    }

    private void appendClusterPublicKey(StringBuilder sb) {
        String clusterPublicKey = rsaHelper.getPublicKeyEncoded();
        if (clusterPublicKey == null) {
            return;
        }
        if (clusterPublicKey.isBlank()) {
            return;
        }

        sb.append("WINDBLOG_CLUSTER_PUBLIC_KEY=").append(clusterPublicKey).append("\n");
    }

    private String buildDockerCompose(EdgeNode node) {
        String containerPrefix = "windblog-edge-" + node.nodeId;

        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");

        sb.append("  edge-db:\n");
        sb.append("    container_name: ").append(containerPrefix).append("-db\n");
        sb.append("    image: postgres:18\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    networks:\n");
        sb.append("      - app-network\n");
        sb.append("    environment:\n");
        sb.append("      POSTGRES_USER: ${EDGE_DB_USER}\n");
        sb.append("      POSTGRES_PASSWORD: ${EDGE_DB_PASSWORD}\n");
        sb.append("      POSTGRES_DB: ${EDGE_DB_NAME}\n");
        sb.append("    volumes:\n");
        sb.append("      - edge-db-data-").append(node.nodeId).append(":/var/lib/postgresql\n");
        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD-SHELL\", \"pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB\"]\n");
        sb.append("      interval: 10s\n");
        sb.append("      timeout: 5s\n");
        sb.append("      retries: 5\n");
        sb.append("\n");

        sb.append("  edge-redis:\n");
        sb.append("    container_name: ").append(containerPrefix).append("-redis\n");
        sb.append("    image: redis:8-alpine\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    networks:\n");
        sb.append("      - app-network\n");
        sb.append("    volumes:\n");
        sb.append("      - edge-redis-data-").append(node.nodeId).append(":/data\n");
        sb.append("    environment:\n");
        sb.append("      REDIS_PASSWORD: ${EDGE_REDIS_PASSWORD}\n");
        sb.append("    command: [\"redis-server\", \"--appendonly\", \"yes\", \"--bind\", \"0.0.0.0\", \"--requirepass\", \"${EDGE_REDIS_PASSWORD}\"]\n");
        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD-SHELL\", \"redis-cli -a \\\"$$REDIS_PASSWORD\\\" ping | grep PONG\"]\n");
        sb.append("      interval: 10s\n");
        sb.append("      timeout: 5s\n");
        sb.append("      retries: 5\n");
        sb.append("\n");

        sb.append("  edge-node:\n");
        sb.append("    container_name: ").append(containerPrefix).append("-node\n");
        sb.append("    image: ${WINDBLOG_EDGE_IMAGE}\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    networks:\n");
        sb.append("      - app-network\n");
        sb.append("    ports:\n");
        sb.append("      - \"${EDGE_GRPC_PORT}:${EDGE_GRPC_PORT}\"\n");
        sb.append("      - \"${EDGE_APP_HTTP_PORT}:8081\"\n");
        sb.append("    volumes:\n");
        sb.append("      - ./certs:/work/certs\n");
        sb.append("    env_file:\n");
        sb.append("      - .env\n");
        sb.append("    environment:\n");
        sb.append("      - QUARKUS_DATASOURCE_JDBC_URL=${EDGE_DATASOURCE_URL}\n");
        sb.append("      - QUARKUS_DATASOURCE_USERNAME=${EDGE_DB_USER}\n");
        sb.append("      - QUARKUS_DATASOURCE_PASSWORD=${EDGE_DB_PASSWORD}\n");
        sb.append("      - QUARKUS_REDIS_HOSTS=${EDGE_REDIS_URL}\n");
        sb.append("      - QUARKUS_HTTP_PORT=8081\n");
        sb.append("      - QUARKUS_GRPC_SERVER_HOST=0.0.0.0\n");
        sb.append("      - QUARKUS_GRPC_SERVER_PORT=${EDGE_GRPC_PORT}\n");
        sb.append("      - QUARKUS_GRPC_SERVER_PLAIN_TEXT=false\n");
        sb.append("      - QUARKUS_GRPC_SERVER_SSL_CERTIFICATE=${EDGE_CERT_PATH:-certs/ca/server.crt}\n");
        sb.append("      - QUARKUS_GRPC_SERVER_SSL_KEY=${EDGE_KEY_PATH:-certs/ca/server.key}\n");
        sb.append("      - QUARKUS_GRPC_SERVER_SSL_TRUST_STORE=${GRPC_SERVER_TRUST_STORE}\n");
        sb.append("      - QUARKUS_GRPC_SERVER_SSL_TRUST_STORE_PASSWORD=${GRPC_SERVER_TRUST_STORE_PASSWORD}\n");
        sb.append("      - QUARKUS_GRPC_SERVER_SSL_CLIENT_AUTH=required\n");
        sb.append("      - QUARKUS_GRPC_CLIENTS_MAIN_NODE_SSL_CERTIFICATE=${EDGE_CLIENT_CERT_PATH:-certs/ca/server.crt}\n");
        sb.append("      - QUARKUS_GRPC_CLIENTS_MAIN_NODE_SSL_KEY=${EDGE_CLIENT_KEY_PATH:-certs/ca/server.key}\n");
        sb.append("      - QUARKUS_GRPC_CLIENTS_MAIN_NODE_SSL_TRUST_CERTIFICATE=${CA_CERT_PATH:-certs/ca/ca.crt}\n");
        sb.append("      - GRPC_CLIENT_CA_CERTIFICATE=${GRPC_CLIENT_CA_CERTIFICATE}\n");
        sb.append("      - GRPC_CLIENT_CERTIFICATE=${GRPC_CLIENT_CERTIFICATE}\n");
        sb.append("      - GRPC_CLIENT_KEY=${GRPC_CLIENT_KEY}\n");
        sb.append("      - GRPC_CLIENT_REWRITE_LOCAL_TARGET=${GRPC_CLIENT_REWRITE_LOCAL_TARGET}\n");
        sb.append("      - MAIN_NODE_GRPC_OVERRIDE_AUTHORITY=main-node\n");
        sb.append("      - QUARKUS_GRPC_CLIENTS__MAIN_NODE__OVERRIDE_AUTHORITY=main-node\n");
        sb.append("      - EDGE_NODE_ID=${EDGE_NODE_ID}\n");
        sb.append("      - EDGE_NODE_REGION=${EDGE_NODE_REGION}\n");
        sb.append("      - EDGE_CONNECTION_TYPE=${EDGE_CONNECTION_TYPE}\n");
        sb.append("      - WINDBLOG_NODE_ROLE=${WINDBLOG_NODE_ROLE}\n");
        sb.append("      - QUARKUS_PROFILE=${QUARKUS_PROFILE}\n");
        sb.append("      - MAIN_NODE_GRPC_HOST=${MAIN_NODE_GRPC_HOST}\n");
        sb.append("      - MAIN_NODE_GRPC_PORT=${MAIN_NODE_GRPC_PORT}\n");
        sb.append("      - USER_JWT_SECRET=${USER_JWT_SECRET}\n");
        sb.append("      - USER_JWT_ISSUER=${USER_JWT_ISSUER}\n");
        sb.append("      - ADMIN_JWT_SECRET=${ADMIN_JWT_SECRET}\n");
        sb.append("      - ADMIN_JWT_ISSUER=${ADMIN_JWT_ISSUER}\n");
        sb.append("      - ADMIN_INIT_PASSWORD=${ADMIN_INIT_PASSWORD}\n");
        sb.append("      - SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD=${SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD:-true}\n");
        sb.append("      - BLOG_URL=${BLOG_URL}\n");
        sb.append("    depends_on:\n");
        sb.append("      edge-db:\n");
        sb.append("        condition: service_healthy\n");
        sb.append("      edge-redis:\n");
        sb.append("        condition: service_healthy\n");
        sb.append("\n");

        sb.append("networks:\n");
        sb.append("  app-network:\n");
        sb.append("    driver: bridge\n");
        sb.append("\n");
        sb.append("volumes:\n");
        sb.append("  edge-db-data-").append(node.nodeId).append(":\n");
        sb.append("  edge-redis-data-").append(node.nodeId).append(":\n");

        return sb.toString();
    }

    private String buildReadme(
            EdgeNode node,
            String imageReference,
            EdgeImageVariant imageVariant
    ) {
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
        sb.append("应用镜像: ").append(imageReference).append("\n");
        sb.append("镜像变体: ").append(imageVariant.getRequestValue()).append("\n");
        sb.append("\n");
        sb.append("主节点签发证书有效期: 24 小时\n");
        sb.append("备用证书有效期: 72 小时\n");
        sb.append("（证书到期前主节点会自动签发新证书，需要重新部署更新）\n");
        sb.append("注意: 从节点启动时会自动生成自签名证书到 certs/ 目录\n");
        sb.append("       主节点签发的证书用于 mTLS 身份验证\n");
        sb.append("       使用 edge profile 启动时会关闭 RabbitMQ 通道，只保留 gRPC 回源和本地数据库/Redis。\n");
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
        sb.append("4. 本节点使用 bridge 网络模式，仅映射受控的 HTTP 和 gRPC 入口；数据库与 Redis 只在内部网络可见。\n");
        sb.append("   编辑 .env 文件确认 EDGE_APP_HTTP_PORT、EDGE_GRPC_PORT 未被占用，并保留 gRPC mTLS 配置。\n");
        sb.append("\n");
        sb.append("5. 如果主节点不在 host.docker.internal 上,\n");
        sb.append("   请编辑 .env 文件修改 MAIN_NODE_GRPC_HOST 为实际主节点地址\n");
        sb.append("\n");
        sb.append("6. 启动服务:\n");
        sb.append("   docker compose up -d\n");
        sb.append("\n");
        sb.append("7. 查看日志确认正常:\n");
        sb.append("   docker compose logs -f edge-node\n");
        sb.append("\n");
        sb.append("========================================\n");
        return sb.toString();
    }

    private String resolveImageReference(
            String requestedImageReference,
            EdgeImageVariant imageVariant
    ) {
        if (requestedImageReference != null && !requestedImageReference.isBlank()) {
            String imageReference = requestedImageReference.trim();
            validateImageReference(imageReference);
            return imageReference;
        }

        String imageRepository = edgeImageRepository;
        if (imageRepository == null || imageRepository.isBlank()) {
            imageRepository = "ghcr.io/skyhhjmk/windblog_quarkus";
        }

        imageRepository = removeTrailingTag(imageRepository.trim());
        return imageRepository + ":" + imageVariant.getDefaultTag();
    }

    private void validateImageReference(String imageReference) {
        if (imageReference.contains("\n") || imageReference.contains("\r")) {
            throw new jakarta.ws.rs.BadRequestException("镜像完全限定名称不能包含换行");
        }
        if (imageReference.contains(" ")) {
            throw new jakarta.ws.rs.BadRequestException("镜像完全限定名称不能包含空格");
        }
        if (!imageReference.contains("/")) {
            throw new jakarta.ws.rs.BadRequestException("镜像完全限定名称必须包含仓库或命名空间");
        }
        if (!imageReference.contains(":") && !imageReference.contains("@")) {
            throw new jakarta.ws.rs.BadRequestException("镜像完全限定名称必须包含 tag 或 digest");
        }
    }

    private String removeTrailingTag(String imageRepository) {
        int lastSlashIndex = imageRepository.lastIndexOf('/');
        int lastColonIndex = imageRepository.lastIndexOf(':');
        if (lastColonIndex > lastSlashIndex) {
            return imageRepository.substring(0, lastColonIndex);
        }
        return imageRepository;
    }

    private void addTextEntry(ZipOutputStream zipOutputStream, String entryPath, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        zipOutputStream.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zipOutputStream.write(bytes);
        zipOutputStream.closeEntry();
    }

    private void addBinaryEntry(ZipOutputStream zipOutputStream, String entryPath, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        zipOutputStream.putNextEntry(entry);
        zipOutputStream.write(content);
        zipOutputStream.closeEntry();
    }

    private int generateRandomHighPort() {
        return ThreadLocalRandom.current().nextInt(20000, 60000);
    }

    private void ensureFixedDeploymentPorts(EdgeNode node) {
        if (node.edgeGrpcPort == null) {
            node.edgeGrpcPort = Integer.valueOf(generateRandomHighPort());
        }
        if (node.edgeDbPort == null) {
            node.edgeDbPort = Integer.valueOf(generateRandomHighPort());
        }
        if (node.edgeRedisPort == null) {
            node.edgeRedisPort = Integer.valueOf(generateRandomHighPort());
        }
        if (node.edgeHttpPort == null) {
            node.edgeHttpPort = Integer.valueOf(generateRandomHighPort());
        }
    }

    private byte[] buildTrustStoreBytes(String caCertificatePem, String trustStorePassword) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream certificateInputStream = new ByteArrayInputStream(caCertificatePem.getBytes(StandardCharsets.UTF_8));
        X509Certificate caCertificate = (X509Certificate) certificateFactory.generateCertificate(certificateInputStream);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", caCertificate);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        keyStore.store(outputStream, trustStorePassword.toCharArray());
        return outputStream.toByteArray();
    }

    private String generateSecretHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        getSecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private SecureRandom getSecureRandom() {
        SecureRandom cached = secureRandom;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = secureRandom;
            if (cached == null) {
                cached = new SecureRandom();
                secureRandom = cached;
            }
            return cached;
        }
    }
}
