package com.biliwind.blog.service.security;

import com.biliwind.blog.controller.api.admin.AdminEdgeNodeApiController;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DeploymentPackageServiceTest.DefaultSecurityProfile.class)
public class DeploymentPackageServiceTest {

    /**
     * Keeps the compatibility assertion independent of a developer's local
     * .env file. The generated edge package must disable the production
     * default-secret guard only when its source node really uses defaults.
     */
    public static class DefaultSecurityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "admin.jwt.secret", "windblog-admin-dev-secret-change-me",
                    "user.jwt.secret", "windblog-user-dev-secret-change-me",
                    "security.event-hash-secret", "windblog-dev-event-hash-secret"
            );
        }
    }

    @Inject
    DeploymentPackageService deploymentPackageService;

    @Inject
    AdminEdgeNodeApiController adminEdgeNodeApiController;

    @Test
    public void testBuildDeploymentZip() throws Exception {
        // 准备一个测试边缘节点
        EdgeNode node = new EdgeNode();
        node.nodeId = "test-node-123";
        node.name = "测试边缘节点";
        node.grpcAddress = "192.168.1.100:9000";
        node.edgeGrpcPort = 9005;
        node.region = BlogRegion.CN;
        node.connectionType = EdgeConnectionType.HEARTBEAT;

        // 生成部署 ZIP 包
        byte[] zipBytes = deploymentPackageService.buildDeploymentZip(node);
        assertNotNull(zipBytes);
        assertTrue(zipBytes.length > 0);

        // 解压并验证包内容
        ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
        ZipInputStream zis = new ZipInputStream(bais);
        ZipEntry entry = zis.getNextEntry();

        String envContent = null;
        String dockerComposeContent = null;
        String deployScriptContent = null;
        boolean hasServerCertificate = false;
        boolean hasServerKey = false;
        boolean hasCaCertificate = false;
        boolean hasTrustStore = false;

        while (entry != null) {
            String name = entry.getName();
            if (name.endsWith(".env")) {
                envContent = readEntryContent(zis);
            }
            if (name.endsWith("docker-compose.yml")) {
                dockerComposeContent = readEntryContent(zis);
            }
            if (name.endsWith("deploy.sh")) {
                deployScriptContent = readEntryContent(zis);
            }
            if (name.endsWith("certs/ca/server.crt")) {
                hasServerCertificate = true;
            }
            if (name.endsWith("certs/ca/server.key")) {
                hasServerKey = true;
            }
            if (name.endsWith("certs/ca/ca.crt")) {
                hasCaCertificate = true;
            }
            if (name.endsWith("certs/ca/truststore.p12")) {
                hasTrustStore = true;
            }
            entry = zis.getNextEntry();
        }
        zis.close();

        // 验证 .env 文件内容
        assertNotNull(envContent);
        assertTrue(envContent.contains("EDGE_NODE_ID=test-node-123"));
        assertTrue(envContent.contains("EDGE_NODE_REGION=cn"));
        assertTrue(envContent.contains("MAIN_NODE_GRPC_HOST="));
        assertTrue(envContent.contains("EDGE_DB_USER=windblog"));
        assertTrue(envContent.matches("(?s).*EDGE_DB_PASSWORD=[0-9a-f]{64}\\n.*"));
        assertTrue(envContent.contains("EDGE_DB_NAME=windblog_edge"));
        assertFalse(envContent.contains("EDGE_DB_PORT="));
        assertTrue(envContent.contains("EDGE_DATASOURCE_URL=jdbc:postgresql://edge-db:5432/windblog_edge"));
        assertTrue(envContent.matches("(?s).*EDGE_REDIS_PASSWORD=[0-9a-f]{64}\\n.*"));
        assertTrue(envContent.contains("EDGE_REDIS_URL=redis://:"));
        assertFalse(envContent.contains("EDGE_REDIS_PORT="));
        assertTrue(envContent.contains("EDGE_APP_HTTP_PORT="));
        assertTrue(envContent.contains("EDGE_APP_GRPC_PORT=9005"));
        assertTrue(envContent.contains("EDGE_GRPC_BIND_IP=127.0.0.1"));
        assertTrue(envContent.contains("WINDBLOG_EDGE_IMAGE=docker.io/hhjmk/windblog_quarkus:latest"));
        assertTrue(envContent.contains("WINDBLOG_EDGE_IMAGE_VARIANT=native-micro"));
        assertTrue(envContent.contains("EDGE_DB_IMAGE=postgres:18-alpine"));
        assertTrue(envContent.contains("WINDBLOG_EDGE_LIGHTWEIGHT=true"));
        assertTrue(envContent.contains("WINDBLOG_WESP_CONFIG_FILE=/work/wesp-config/wesp-config.json"));
        assertTrue(envContent.contains("WINDBLOG_WESP_BLOCK_STORE=/work/wesp-blocks"));
        assertTrue(envContent.contains("MEDIA_UPLOAD_DIR=/work/uploads"));
        assertTrue(envContent.contains("WINDBLOG_OUTBOX_ENABLED=false"));
        assertTrue(envContent.contains("WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED=false"));
        assertTrue(envContent.contains("WINDBLOG_STORAGE_METRICS_ENABLED=false"));
        assertTrue(envContent.contains("QUARKUS_LOG_FILE_ENABLED=false"));
        assertTrue(envContent.contains("USER_JWT_SECRET="));
        assertTrue(envContent.contains("USER_JWT_ISSUER="));
        assertTrue(envContent.contains("ADMIN_JWT_SECRET="));
        assertTrue(envContent.contains("ADMIN_JWT_ISSUER="));
        assertFalse(envContent.contains("ADMIN_INIT_PASSWORD="));
        assertTrue(envContent.contains("SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD=false"));
        assertTrue(envContent.contains("BLOG_URL="));
        assertTrue(envContent.contains("GRPC_CLIENT_CA_CERTIFICATE=certs/ca/ca.crt"));
        assertTrue(envContent.contains("GRPC_CLIENT_CERTIFICATE=certs/ca/server.crt"));
        assertTrue(envContent.contains("GRPC_CLIENT_KEY=certs/ca/server.key"));
        assertTrue(envContent.contains("GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK=false"));
        assertTrue(envContent.contains("GRPC_SERVER_CLIENT_AUTH=required"));
        assertTrue(envContent.matches("(?s).*GRPC_SERVER_TRUST_STORE_PASSWORD=[0-9a-f]{64}\\n.*"));
        assertTrue(hasServerCertificate);
        assertTrue(hasServerKey);
        assertTrue(hasCaCertificate);
        assertTrue(hasTrustStore);

        // 仅入口端口对宿主机开放，数据库和 Redis 保持内部网络。
        int httpPort = extractPort(envContent, "EDGE_APP_HTTP_PORT=");
        assertTrue(httpPort >= 20000 && httpPort <= 60000);
        assertTrue(envContent.contains("QUARKUS_PROFILE=edge"));

        // 验证 docker-compose.yml 内容
        assertNotNull(dockerComposeContent);
        assertTrue(dockerComposeContent.contains("edge-db:"));
        assertTrue(dockerComposeContent.contains("image: ${EDGE_DB_IMAGE:-postgres:18-alpine}"));
        assertTrue(dockerComposeContent.contains("container_name: windblog-edge-test-node-123-db"));
        assertFalse(dockerComposeContent.contains("${EDGE_DB_PORT}:5432"));
        assertTrue(dockerComposeContent.contains("edge-redis:"));
        assertTrue(dockerComposeContent.contains("container_name: windblog-edge-test-node-123-redis"));
        assertFalse(dockerComposeContent.contains("${EDGE_REDIS_PORT}:6379"));
        assertTrue(dockerComposeContent.contains("edge-node:"));
        assertTrue(dockerComposeContent.contains("container_name: windblog-edge-test-node-123-node"));
        assertTrue(dockerComposeContent.contains("user: \"${EDGE_RUNTIME_UID:-1000}:${EDGE_RUNTIME_GID:-1000}\""));
        assertTrue(dockerComposeContent.contains("\"${EDGE_APP_HTTP_PORT}:8081\""));
        assertTrue(dockerComposeContent.contains(
                "\"${EDGE_GRPC_BIND_IP:-127.0.0.1}:${EDGE_GRPC_PORT}:${EDGE_GRPC_PORT}\""
        ));
        assertTrue(dockerComposeContent.contains("QUARKUS_DATASOURCE_JDBC_URL=${EDGE_DATASOURCE_URL}"));
        assertTrue(dockerComposeContent.contains("QUARKUS_PROFILE=${QUARKUS_PROFILE}"));
        assertFalse(dockerComposeContent.contains("ADMIN_INIT_PASSWORD=${ADMIN_INIT_PASSWORD}"));
        assertTrue(dockerComposeContent.contains(
                "SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD=${SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD:-true}"
        ));
        assertTrue(dockerComposeContent.contains("QUARKUS_GRPC_SERVER_SSL_CERTIFICATE=${EDGE_CERT_PATH:-certs/ca/server.crt}"));
        assertTrue(dockerComposeContent.contains("QUARKUS_GRPC_SERVER_SSL_CLIENT_AUTH=required"));
        assertTrue(dockerComposeContent.contains("QUARKUS_GRPC_SERVER_SSL_TRUST_STORE_PASSWORD=${GRPC_SERVER_TRUST_STORE_PASSWORD}"));
        assertTrue(dockerComposeContent.contains("redis-cli -a \\\"$$REDIS_PASSWORD\\\" ping | grep PONG"));
        assertFalse(dockerComposeContent.contains("$$EDGE_REDIS_PASSWORD"));
        assertTrue(dockerComposeContent.contains("QUARKUS_GRPC_CLIENTS_MAIN_NODE_SSL_TRUST_CERTIFICATE=${CA_CERT_PATH:-certs/ca/ca.crt}"));
        assertTrue(dockerComposeContent.contains("depends_on:"));
        assertTrue(dockerComposeContent.contains("edge-db:"));
        assertTrue(dockerComposeContent.contains("edge-redis:"));
        assertTrue(dockerComposeContent.contains("volumes:"));
        assertTrue(dockerComposeContent.contains("edge-db-data-test-node-123:"));
        assertTrue(dockerComposeContent.contains("edge-redis-data-test-node-123:"));
        assertTrue(dockerComposeContent.contains("mem_limit: 384m"));
        assertTrue(dockerComposeContent.contains("./data/wesp-blocks:/work/wesp-blocks"));
        assertTrue(dockerComposeContent.contains("./data/wesp-config:/work/wesp-config"));
        assertTrue(dockerComposeContent.contains("./data/rsa-keys:/work/rsa_keys"));

        assertNotNull(deployScriptContent);
        assertTrue(deployScriptContent.contains("export EDGE_RUNTIME_UID=\"${EDGE_RUNTIME_UID:-$(id -u)}\""));
        assertTrue(deployScriptContent.contains("compose=(docker compose)"));
        assertTrue(deployScriptContent.contains("mkdir -p data/uploads data/wesp-blocks data/wesp-config data/rsa-keys"));
        assertTrue(deployScriptContent.contains("pull edge-db edge-redis edge-node"));
        assertTrue(deployScriptContent.contains("/q/health/ready"));
        assertTrue(deployScriptContent.contains("compose[@]}"));

        Path deployScript = Files.createTempFile("windblog-edge-deploy-", ".sh");
        try {
            Files.writeString(deployScript, deployScriptContent, StandardCharsets.UTF_8);
            Process syntaxCheck = new ProcessBuilder("bash", "-n", deployScript.toString())
                    .redirectErrorStream(true)
                    .start();
            String syntaxOutput = new String(syntaxCheck.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, syntaxCheck.waitFor(), syntaxOutput);
        } finally {
            Files.deleteIfExists(deployScript);
        }
    }

    @Test
    public void shouldUseRequestedFullyQualifiedImageReference() throws Exception {
        EdgeNode node = new EdgeNode();
        node.nodeId = "custom-image-node";
        node.name = "自定义镜像节点";
        node.edgeGrpcPort = 9005;
        node.region = BlogRegion.CN;
        node.connectionType = EdgeConnectionType.HEARTBEAT;

        byte[] zipBytes = deploymentPackageService.buildDeploymentZip(
                node,
                "registry.example.com/team/windblog:v2-jvm",
                EdgeImageVariant.JVM
        );
        String envContent = readZipTextEntry(zipBytes, ".env");

        assertNotNull(envContent);
        assertTrue(envContent.contains(
                "WINDBLOG_EDGE_IMAGE=registry.example.com/team/windblog:v2-jvm"
        ));
        assertTrue(envContent.contains("WINDBLOG_EDGE_IMAGE_VARIANT=jvm"));
    }

    @Test
    public void shouldRejectImageReferenceWithoutTagOrDigest() {
        EdgeNode node = new EdgeNode();
        node.nodeId = "invalid-image-node";
        node.name = "无效镜像节点";
        node.edgeGrpcPort = 9005;
        node.region = BlogRegion.CN;
        node.connectionType = EdgeConnectionType.HEARTBEAT;

        assertThrows(
                jakarta.ws.rs.BadRequestException.class,
                () -> deploymentPackageService.buildDeploymentZip(
                        node,
                        "registry.example.com/team/windblog",
                        EdgeImageVariant.NATIVE
                )
        );
    }

    @Test
    public void testCreateEdgeNodeWithActivePoll() {
        // 1. 校验使用 ACTIVE_POLL 模式下，如果不填 IP，是否抛出 BadRequestException 异常
        AdminEdgeNodeApiController.CreateNodeRequest requestWithoutIp =
                new AdminEdgeNodeApiController.CreateNodeRequest(
                        "active-node-no-ip",
                        "无IP主动轮询节点",
                        BlogRegion.CN,
                        EdgeConnectionType.ACTIVE_POLL,
                        null,
                        null
                );

        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> {
            adminEdgeNodeApiController.create(requestWithoutIp);
        });

        // 2. 校验在 ACTIVE_POLL 模式下填写 IP 能成功创建并正确拼接 grpcAddress
        AdminEdgeNodeApiController.CreateNodeRequest requestWithIp =
                new AdminEdgeNodeApiController.CreateNodeRequest(
                        "active-node-with-ip",
                        "带IP主动轮询节点",
                        BlogRegion.CN,
                        EdgeConnectionType.ACTIVE_POLL,
                        null,
                        "192.168.1.100"
                );

        EdgeNode node = adminEdgeNodeApiController.create(requestWithIp);
        assertNotNull(node);
        assertEquals("active-node-with-ip", node.nodeId);
        assertNotNull(node.edgeGrpcPort);
        assertTrue(node.edgeGrpcPort >= 20000 && node.edgeGrpcPort <= 60000);
        assertEquals("192.168.1.100:" + node.edgeGrpcPort, node.grpcAddress);
        assertTrue(node.isTrusted);

        // 清理数据库脏数据
        cleanupNode("active-node-with-ip");
    }

    @Transactional
    void cleanupNode(String nodeId) {
        EdgeNode.deleteById(nodeId);
    }

    private int extractPort(String content, String key) {
        int idx = content.indexOf(key);
        if (idx == -1) {
            return -1;
        }
        int start = idx + key.length();
        int end = content.indexOf("\n", start);
        if (end == -1) {
            end = content.length();
        }
        String portStr = content.substring(start, end).trim();
        return Integer.parseInt(portStr);
    }

    private String readEntryContent(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len = zis.read(buffer);
        while (len > 0) {
            baos.write(buffer, 0, len);
            len = zis.read(buffer);
        }
        return baos.toString("UTF-8");
    }

    private String readZipTextEntry(byte[] zipBytes, String entrySuffix) throws Exception {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(zipBytes);
        ZipInputStream zipInputStream = new ZipInputStream(byteArrayInputStream);
        ZipEntry zipEntry = zipInputStream.getNextEntry();

        while (zipEntry != null) {
            if (zipEntry.getName().endsWith(entrySuffix)) {
                String entryContent = readEntryContent(zipInputStream);
                zipInputStream.close();
                return entryContent;
            }
            zipEntry = zipInputStream.getNextEntry();
        }

        zipInputStream.close();
        return null;
    }
}
