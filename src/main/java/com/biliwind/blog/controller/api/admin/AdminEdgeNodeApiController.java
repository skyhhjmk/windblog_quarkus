package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.EdgeNodeDataStatusResponse;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.model.EdgeSyncRecord;
import com.biliwind.blog.service.edge.EdgeNodeRegistry;
import io.quarkus.panache.common.Page;
import com.biliwind.blog.service.security.CertificateService;
import com.biliwind.blog.service.security.DeploymentPackageService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
    com.biliwind.blog.service.edge.PrimaryEdgeChannelRegistry primaryEdgeChannelRegistry;

    @Inject
    com.biliwind.blog.service.edge.EdgeNodeAvailabilityService edgeNodeAvailabilityService;

    @Inject
    CertificateService certificateService;

    @Inject
    DeploymentPackageService deploymentPackageService;

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
    @Operation(summary = "创建边缘节点", description = "创建新的边缘节点，同时签发 mTLS 证书。创建后可通过 deployment-zip 接口下载部署包。")
    public EdgeNode create(CreateNodeRequest request) {
        if (request.nodeId() == null || request.nodeId().isEmpty()) {
            throw new BadRequestException("nodeId 不能为空");
        }
        if (EdgeNode.findByNodeId(request.nodeId()) != null) {
            throw new BadRequestException("nodeId 已存在");
        }

        EdgeNode node = new EdgeNode();
        node.nodeId = request.nodeId();

        String reqName = request.nodeName();
        if (reqName != null && !reqName.isEmpty()) {
            node.name = reqName;
        } else {
            node.name = request.nodeId();
        }

        BlogRegion reqRegion = request.region();
        if (reqRegion != null) {
            node.region = reqRegion;
        } else {
            node.region = BlogRegion.GLOBAL;
        }

        EdgeConnectionType reqConn = request.connectionType();
        if (reqConn != null) {
            node.connectionType = reqConn;
        } else {
            node.connectionType = EdgeConnectionType.HEARTBEAT;
        }

        Integer reqPort = request.edgeGrpcPort();
        if (reqPort != null) {
            node.edgeGrpcPort = reqPort;
        } else {
            node.edgeGrpcPort = Integer.valueOf(generateRandomHighPort());
        }
        node.edgeDbPort = Integer.valueOf(generateRandomHighPort());
        node.edgeRedisPort = Integer.valueOf(generateRandomHighPort());
        node.edgeHttpPort = Integer.valueOf(generateRandomHighPort());

        // 主动连接模式校验和通信地址填充
        if (node.connectionType == EdgeConnectionType.ACTIVE_POLL) {
            String ip = request.nodeIp();
            if (ip == null || ip.trim().isEmpty()) {
                throw new BadRequestException("使用主动轮询模式时必须填写节点 IP");
            }
            node.grpcAddress = ip.trim() + ":" + node.edgeGrpcPort;
            node.isTrusted = true;
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
        if (request.edgeGrpcPort() != null) node.edgeGrpcPort = request.edgeGrpcPort();

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

    @DELETE
    @Path("/{nodeId}")
    @Transactional
    @Operation(summary = "删除边缘节点")
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

    @GET
    @Path("/{nodeId}/sync-records")
    @Operation(summary = "获取边缘节点同步记录", description = "返回最近的增量和全量同步投递记录，用于排查失败、漏同步和节点收敛问题")
    public List<EdgeSyncRecord> getSyncRecords(@PathParam("nodeId") String nodeId,
                                               @QueryParam("status") String status,
                                               @QueryParam("page") @DefaultValue("1") int page,
                                               @QueryParam("pageSize") @DefaultValue("50") int pageSize) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        int safePage = page;
        if (safePage < 1) {
            safePage = 1;
        }
        int safePageSize = pageSize;
        if (safePageSize < 1) {
            safePageSize = 50;
        }
        if (safePageSize > 200) {
            safePageSize = 200;
        }

        if (status != null && !status.isBlank()) {
            return EdgeSyncRecord.find(
                            "nodeId = ?1 and status = ?2 order by updatedAt desc",
                            nodeId,
                            status.trim())
                    .page(Page.of(safePage - 1, safePageSize))
                    .list();
        }

        return EdgeSyncRecord.find("nodeId = ?1 order by updatedAt desc", nodeId)
                .page(Page.of(safePage - 1, safePageSize))
                .list();
    }

    @GET
    @Path("/{nodeId}/data-status")
    @Operation(summary = "获取边缘节点数据通道状态", description = "返回持久通道、只读模式、心跳指标和同步进度")
    public EdgeNodeDataStatusResponse getDataStatus(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        boolean channelOnline = primaryEdgeChannelRegistry.hasOnlineChannel(nodeId);
        String effectiveNodeStatus = node.status;
        if (channelOnline) {
            effectiveNodeStatus = "ONLINE";
        }
        boolean primaryOnline = channelOnline;
        boolean readOnly = !channelOnline;
        String readOnlyMessage = "";
        if (readOnly) {
            readOnlyMessage = "持久数据通道未连接，节点离线时应处于只读模式";
        }

        if (node.metrics != null) {
            String metricPrimaryOnline = node.metrics.get("primaryOnline");
            if (metricPrimaryOnline != null) {
                primaryOnline = Boolean.parseBoolean(metricPrimaryOnline);
            }
            String metricReadOnly = node.metrics.get("readOnly");
            if (metricReadOnly != null) {
                readOnly = Boolean.parseBoolean(metricReadOnly);
            }
            String metricReadOnlyMessage = node.metrics.get("readOnlyMessage");
            if (metricReadOnlyMessage != null && !metricReadOnlyMessage.isBlank()) {
                readOnlyMessage = metricReadOnlyMessage;
            }
        }

        return new EdgeNodeDataStatusResponse(
                node.nodeId,
                effectiveNodeStatus,
                Boolean.TRUE.equals(node.isEnabled),
                node.isTrusted,
                channelOnline,
                primaryOnline,
                readOnly,
                readOnlyMessage,
                primaryEdgeChannelRegistry.getConnectedAt(nodeId),
                node.lastHeartbeat,
                node.metrics,
                edgeNodeAvailabilityService.calculateRates(nodeId),
                syncService.getSyncStatus(nodeId)
        );
    }

    @GET
    @Path("/{nodeId}/availability-history")
    @Operation(summary = "获取边缘节点在线历史", description = "返回近 1-30 天在线率采样、在线时段和日历数据")
    public com.biliwind.blog.service.edge.EdgeNodeAvailabilityService.AvailabilityHistory getAvailabilityHistory(
            @PathParam("nodeId") String nodeId,
            @QueryParam("days") @DefaultValue("30") int days) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        return edgeNodeAvailabilityService.getHistory(nodeId, days);
    }

    @POST
    @Path("/{nodeId}/issue-certificate")
    @Transactional
    @Operation(summary = "签发节点证书", description = "为边缘节点生成一对新的 mTLS 证书（24h 主证书 + 72h 备用证书），并更新数据库记录")
    public EdgeNode issueCertificate(@PathParam("nodeId") String nodeId) throws Exception {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) throw new NotFoundException("节点不存在");

        CertificateService.GeneratedCertificate primary = certificateService.generateNodeCertificate(nodeId, 24);
        CertificateService.GeneratedCertificate backup = certificateService.generateNodeCertificate(nodeId, 72);

        node.certificateSerial = primary.serialNumber();
        node.certificateExpiry = primary.expiry();
        node.certificateBackupSerial = backup.serialNumber();
        node.certificateBackupExpiry = backup.expiry();
        node.certificateRevoked = false;
        node.persist();

        return node;
    }

    @GET
    @Path("/{nodeId}/deployment-zip")
    @Transactional
    @Produces("application/zip")
    @Operation(summary = "下载部署 ZIP 包", description = "生成并下载包含证书、.env、docker-compose.yml 的完整部署 ZIP 包，解压后即可使用 docker compose up -d 启动")
    public Response downloadDeploymentZip(@PathParam("nodeId") String nodeId) throws Exception {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        byte[] zipBytes = deploymentPackageService.buildDeploymentZip(node);
        node.persist();

        String fileName = "windblog-edge-" + nodeId + ".zip";

        return Response.ok(zipBytes)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Content-Type", "application/zip")
                .build();
    }

    /**
     * 创建边缘节点的请求参数
     */
    public record CreateNodeRequest(
            String nodeId,
            String nodeName,
            BlogRegion region,
            EdgeConnectionType connectionType,
            Integer edgeGrpcPort,
            String nodeIp
    ) {
    }

    private int generateRandomHighPort() {
        return ThreadLocalRandom.current().nextInt(20000, 60000);
    }
}
