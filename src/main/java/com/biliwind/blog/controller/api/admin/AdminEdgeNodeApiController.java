package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.service.edge.EdgeNodeRegistry;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/admin/edge-nodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin Edge Node", description = "边缘节点管理")
public class AdminEdgeNodeApiController {

    @Inject
    EdgeNodeRegistry registry;

    @Inject
    com.biliwind.blog.service.edge.EdgeDataSyncService syncService;

    @Inject
    com.biliwind.blog.service.security.CertificateService certificateService;

    @GET
    @Operation(summary = "获取所有边缘节点", description = "获取所有已注册的边缘节点及其状态")
    public List<EdgeNode> list() {
        return registry.getAllNodes();
    }

    @GET
    @Path("/{nodeId}")
    @Operation(summary = "获取边缘节点详情", description = "获取单个边缘节点的详细配置及最后活跃指标")
    public EdgeNode get(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }
        return node;
    }

    @POST
    @Transactional
    @Operation(summary = "手动添加边缘节点", description = "手动录入边缘节点，通常用于主节点主动连接从节点的场景")
    public EdgeNode create(EdgeNode node) {
        if (node.nodeId == null || node.nodeId.isEmpty()) {
            throw new BadRequestException("nodeId 不能为空");
        }
        if (EdgeNode.findByNodeId(node.nodeId) != null) {
            throw new BadRequestException("nodeId 已存在");
        }
        node.persist();
        return node;
    }

    @PUT
    @Path("/{nodeId}")
    @Transactional
    @Operation(summary = "修改边缘节点配置", description = "修改边缘节点的名称、地址、区域和连接模式")
    public EdgeNode update(@PathParam("nodeId") String nodeId, EdgeNodeUpdateRequest request) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        if (request.name() != null) node.name = request.name();
        if (request.externalUrl() != null) node.externalUrl = request.externalUrl();
        if (request.apiUrl() != null) node.apiUrl = request.apiUrl();
        if (request.grpcAddress() != null) node.grpcAddress = request.grpcAddress();
        if (request.region() != null) node.region = request.region();
        if (request.connectionType() != null) node.connectionType = request.connectionType();
        if (request.isEnabled() != null) node.isEnabled = request.isEnabled();

        node.persist();
        return node;
    }

    @PUT
    @Path("/{nodeId}/toggle")
    @Operation(summary = "启用/禁用边缘节点", description = "手动控制边缘节点是否对外服务")
    public Response toggle(@PathParam("nodeId") String nodeId, @QueryParam("enabled") boolean enabled) {
        registry.toggleNodeEnabled(nodeId, enabled);
        return Response.ok().build();
    }

    public Response delete(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null) {
            node.delete();
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/{nodeId}/sync")
    @Operation(summary = "手动触发同步", description = "触发主节点向指定边缘节点全量推送所有标签和文章")
    public Response triggerSync(@PathParam("nodeId") String nodeId, @QueryParam("force") @DefaultValue("false") boolean force) {
        syncService.triggerFullSync(nodeId, force);
        return Response.accepted().build();
    }

    @GET
    @Path("/{nodeId}/sync-status")
    @Operation(summary = "获取同步进度", description = "获取当前节点的同步进度（仅包含正在进行或最近一次的手动同步任务）")
    public com.biliwind.blog.service.edge.EdgeDataSyncService.SyncProgress getSyncStatus(@PathParam("nodeId") String nodeId) {
        return syncService.getSyncStatus(nodeId);
    }

    @POST
    @Path("/{nodeId}/issue-certificate")
    @Transactional
    @Operation(summary = "签发节点证书", description = "为主节点生成一对新的 mTLS 证书（24h 主证书 + 72h 备用证书）")
    public EdgeNode issueCertificate(@PathParam("nodeId") String nodeId) throws Exception {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) throw new NotFoundException("节点不存在");

        // 签发 24 小时主证书
        var primary = certificateService.generateNodeCertificate(nodeId, 24);
        node.certificateSerial = primary.serialNumber();
        node.certificateExpiry = primary.expiry();

        // 签发 72 小时备用证书
        var backup = certificateService.generateNodeCertificate(nodeId, 72);
        node.certificateBackupSerial = backup.serialNumber();
        node.certificateBackupExpiry = backup.expiry();

        node.certificateRevoked = false;
        node.persist();

        // 将证书内容放入响应中供下载（通过 DTO 包装或直接返回 node，但在 node 中不存储 PEM）
        // 这里我们返回 node，但实际下载 PEM 需要一个专门的 DTO
        return node;
    }

    @GET
    @Path("/{nodeId}/deployment-package")
    @Operation(summary = "获取部署包信息", description = "获取包含证书 PEM、环境变量和 Docker Compose 配置的部署包数据")
    public DeploymentPackage getDeploymentPackage(@PathParam("nodeId") String nodeId) throws Exception {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) throw new NotFoundException("节点不存在");

        // 注意：由于证书 PEM 不存储在数据库中，如果是通过 GET 请求获取，
        // 实际上需要重新签发或从缓存中获取。
        // 这里为了简化，我们让 issue-certificate 返回完整数据，或者让该接口直接重新签发。
        var primary = certificateService.generateNodeCertificate(nodeId, 24);
        var backup = certificateService.generateNodeCertificate(nodeId, 72);

        // 更新数据库中的序列号
        node.certificateSerial = primary.serialNumber();
        node.certificateExpiry = primary.expiry();
        node.certificateBackupSerial = backup.serialNumber();
        node.certificateBackupExpiry = backup.expiry();
        node.persist();

        String envContent = String.format(
                "EDGE_NODE_ID=%s\n" +
                        "EDGE_NODE_REGION=%s\n" +
                        "MAIN_NODE_GRPC_HOST=%s\n" +
                        "MAIN_NODE_GRPC_PORT=9000\n" +
                        "EDGE_CERT_PATH=/deploy/certs/server.crt\n" +
                        "EDGE_KEY_PATH=/deploy/certs/server.key\n" +
                        "CA_CERT_PATH=/deploy/certs/ca.crt\n",
                node.nodeId, node.region.getCode(), "main-node-host-or-ip"
        );

        String dockerCompose =
                "version: '3.8'\n" +
                        "services:\n" +
                        "  edge-node:\n" +
                        "    image: biliwind/windblog-edge-node:latest\n" +
                        "    container_name: windblog-edge-" + node.nodeId + "\n" +
                        "    volumes:\n" +
                        "      - ./certs:/deploy/certs:ro\n" +
                        "    environment:\n" +
                        "      - EDGE_NODE_ID=" + node.nodeId + "\n" +
                        "      - MAIN_NODE_GRPC_HOST=host.docker.internal\n" +
                        "    ports:\n" +
                        "      - \"8082:8082\"\n" +
                        "    extra_hosts:\n" +
                        "      - \"host.docker.internal:host-gateway\"\n";

        return new DeploymentPackage(
                primary.certificatePem(),
                primary.privateKeyPem(),
                backup.certificatePem(),
                backup.privateKeyPem(),
                primary.caCertificatePem(),
                envContent,
                dockerCompose
        );
    }

    public record DeploymentPackage(
            String primaryCert,
            String primaryKey,
            String backupCert,
            String backupKey,
            String caCert,
            String envFile,
            String dockerCompose
    ) {
    }
}
