package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.EdgeNodeDataStatusResponse;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.model.EdgeSyncRecord;
import com.biliwind.blog.service.edge.EdgeNodeRegistry;
import com.biliwind.blog.service.edge.WespRuntimeConfig;
import com.biliwind.blog.service.edge.WespNodeConnectionService;
import com.biliwind.blog.service.edge.WespSyncService;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.security.CertificateRenewalService;
import com.biliwind.blog.service.security.DeploymentPackageService;
import com.biliwind.blog.service.security.EdgeImageVariant;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import io.quarkus.runtime.annotations.RegisterForReflection;

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
    CertificateRenewalService certificateRenewalService;

    @Inject
    DeploymentPackageService deploymentPackageService;

    @Inject
    WespSyncService wespSyncService;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    WespNodeConnectionService wespNodeConnectionService;

    @Inject
    WespRuntimeConfig wespRuntimeConfig;

    @GET
    @Operation(summary = "获取所有边缘节点", description = "获取所有已注册的边缘节点及其状态")
    @Transactional
    public List<EdgeNode> list() {
        return registry.getAllNodes();
    }

    @POST
    @Path("/connect")
    @Operation(summary = "新增并连接边缘节点",
            description = "先探测目标节点，再使用目标管理员凭据登录并写入 WESP 主动连接配置")
    public Response connect(WespNodeConnectionService.ConnectionRequest request) {
        try {
            return Response.ok(wespNodeConnectionService.connect(request)).build();
        } catch (WebApplicationException exception) {
            Response response = exception.getResponse();
            int status = response == null ? Response.Status.BAD_GATEWAY.getStatusCode() : response.getStatus();
            return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(java.util.Map.of("success", false, "message",
                            exception.getMessage() == null ? "节点连接失败" : exception.getMessage()))
                    .build();
        } catch (RuntimeException exception) {
            return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(java.util.Map.of("success", false, "message", "节点连接失败，请检查目标地址和管理员凭据"))
                    .build();
        }
    }

    @GET
    @Path("/connect/probe")
    @Operation(summary = "探测目标边缘节点", description = "仅检查目标节点健康状态，不发送管理员凭据")
    public Response probe(@QueryParam("targetUrl") String targetUrl) {
        try {
            return Response.ok(wespNodeConnectionService.probe(targetUrl)).build();
        } catch (WebApplicationException exception) {
            Response response = exception.getResponse();
            int status = response == null ? Response.Status.BAD_GATEWAY.getStatusCode() : response.getStatus();
            return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(java.util.Map.of(
                            "success", false,
                            "reachable", false,
                            "message", exception.getMessage() == null ? "目标节点不可达" : exception.getMessage()))
                    .build();
        } catch (RuntimeException exception) {
            return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(java.util.Map.of(
                            "success", false,
                            "reachable", false,
                            "message", "目标节点不可达，请检查地址、端口和节点是否已启动"))
                    .build();
        }
    }

    /**
     * Target-side endpoint used only after a successful target admin login.
     * It is intentionally local on an edge node so the write-routing filter
     * cannot forward the bootstrap back to the primary.
     */
    @POST
    @Path("/connection/bootstrap")
    @Transactional
    @Operation(summary = "写入 WESP 运行配置", description = "由已登录的目标管理员完成一次性节点引导")
    public Response bootstrap(ConnectionBootstrapRequest request) {
        if (request == null || blank(request.nodeId()) || blank(request.peerUrl())
                || blank(request.authToken()) || blank(request.tenantId())
                || blank(request.datasetId()) || blank(request.incarnation())) {
            throw new BadRequestException("WESP 连接引导参数不完整");
        }
        if (request.nodeId().length() > 100 || request.authToken().length() < 32
                || request.authToken().length() > 512 || request.incarnation().length() > 100) {
            throw new BadRequestException("WESP 连接引导参数无效");
        }
        try {
            wespRuntimeConfig.applyBootstrap(request.nodeId().trim(), request.peerUrl().trim(),
                    request.authToken().trim(), request.tenantId().trim(), request.datasetId().trim(),
                    request.incarnation().trim());
            wespSyncService.refreshRuntimeConfig();

            EdgeNode node = EdgeNode.findByNodeId(request.nodeId().trim());
            if (node == null) {
                node = new EdgeNode();
                node.nodeId = request.nodeId().trim();
            }
            node.name = blank(request.nodeName()) ? node.nodeId : request.nodeName().trim();
            node.region = request.region() == null ? BlogRegion.GLOBAL : request.region();
            node.connectionType = EdgeConnectionType.WESP;
            node.apiUrl = request.peerUrl().trim().replaceAll("/+$", "");
            node.externalUrl = blank(request.externalUrl()) ? null : request.externalUrl().trim();
            node.status = "CONFIGURED";
            node.isEnabled = true;
            node.isTrusted = true;
            if (node.metrics == null) node.metrics = new java.util.HashMap<>();
            node.metrics.put("connection", "admin-login-bootstrap");
            node.persist();
            return Response.ok(java.util.Map.of("success", true, "nodeId", node.nodeId,
                    "status", node.status, "message", "WESP 运行配置已保存，节点将主动连接主节点")).build();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
    }

    @GET
    @Path("/{nodeId}")
    @Operation(summary = "获取边缘节点详情", description = "获取单个边缘节点的详细配置及最后活跃指标")
    @Transactional
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
        node.externalUrl = request.externalUrl();
        node.apiUrl = request.apiUrl();

        // 主动连接模式校验和通信地址填充
        if (node.connectionType == EdgeConnectionType.ACTIVE_POLL) {
            String ip = request.nodeIp();
            if (ip == null || ip.trim().isEmpty()) {
                throw new BadRequestException("使用主动轮询模式时必须填写节点 IP");
            }
            node.grpcAddress = ip.trim() + ":" + node.edgeGrpcPort;
            node.isTrusted = true;
        }

        if (node.connectionType == EdgeConnectionType.WESP) {
            if (node.apiUrl == null || node.apiUrl.isBlank()) {
                throw new BadRequestException("WESP 模式必须填写主节点连接域名");
            }
            if (!(node.apiUrl.startsWith("http://") || node.apiUrl.startsWith("https://"))) {
                throw new BadRequestException("WESP 主节点连接域名必须是 http(s) 地址");
            }
            node.status = "CONFIGURED";
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
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null && node.connectionType == EdgeConnectionType.WESP
                && wespSyncService.isEnabled()) {
            if (nodeRoleService.isEdgeNode()) {
                // A home admin must ask the primary to create the snapshot;
                // appending a SYNC_REQUEST locally would be rejected by the
                // primary because its target node is the home itself.
                try {
                    wespSyncService.requestFullSyncFromPrimary(force);
                } catch (IllegalStateException exception) {
                    throw new WebApplicationException(
                            "无法向 WESP 主节点请求全量同步", Response.Status.BAD_GATEWAY);
                }
            } else {
                // WESP has no inbound path to a home node. The primary appends
                // the request and current public snapshot to its operation log;
                // the home consumes them during its next outbound pull.
                wespSyncService.triggerFullSync(nodeId, force);
            }
            return Response.accepted().build();
        }
        syncService.triggerFullSync(nodeId, force);
        return Response.accepted().build();
    }

    @GET
    @Path("/{nodeId}/sync-status")
    @Operation(summary = "获取同步进度", description = "获取当前节点的同步进度（仅包含正在进行或最近一次的手动同步任务）")
    @Transactional
    public com.biliwind.blog.service.edge.EdgeDataSyncService.SyncProgress getSyncStatus(@PathParam("nodeId") String nodeId) {
        return syncService.getSyncStatus(nodeId);
    }

    @GET
    @Path("/{nodeId}/sync-records")
    @Operation(summary = "获取边缘节点同步记录", description = "返回最近的增量和全量同步投递记录，用于排查失败、漏同步和节点收敛问题")
    @Transactional
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
    @Transactional
    public EdgeNodeDataStatusResponse getDataStatus(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        boolean wespNode = node.connectionType == EdgeConnectionType.WESP;
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        boolean channelOnline = edgeNodeAvailabilityService.isCurrentlyOnline(node, now);
        String effectiveNodeStatus = node.status;
        if (channelOnline) effectiveNodeStatus = "ONLINE";
        else if (wespNode && "ONLINE".equals(node.status)) effectiveNodeStatus = "OFFLINE";
        boolean primaryOnline = channelOnline;
        boolean readOnly = !channelOnline;
        String readOnlyMessage = "";
        if (readOnly) {
            readOnlyMessage = wespNode
                    ? "WESP 尚未收到节点主动会话，节点离线时应处于只读模式"
                    : "持久数据通道未连接，节点离线时应处于只读模式";
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
                wespNode ? node.lastHeartbeat : primaryEdgeChannelRegistry.getConnectedAt(nodeId),
                node.lastHeartbeat,
                node.metrics,
                edgeNodeAvailabilityService.calculateRates(nodeId),
                syncService.getSyncStatus(nodeId)
        );
    }

    @GET
    @Path("/{nodeId}/availability-history")
    @Operation(summary = "获取边缘节点在线历史", description = "返回近 1-30 天在线率采样、在线时段和日历数据")
    @Transactional
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
    @Operation(summary = "手动续签节点证书", description = "通过在线持久通道向边缘节点下发新证书，安装成功后更新数据库并重启节点")
    public EdgeNode issueCertificate(@PathParam("nodeId") String nodeId) throws Exception {
        return certificateRenewalService.renew(nodeId);
    }

    @GET
    @Path("/{nodeId}/deployment-zip")
    @Transactional
    @Produces("application/zip")
    @Operation(summary = "下载部署 ZIP 包", description = "生成并下载包含证书、.env、docker-compose.yml 的完整部署 ZIP 包，解压后即可使用 docker compose up -d 启动")
    public Response downloadDeploymentZip(
            @PathParam("nodeId") String nodeId,
            @QueryParam("image") String imageReference,
            @QueryParam("variant") @DefaultValue("native-micro") String variant
    ) throws Exception {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        EdgeImageVariant imageVariant = EdgeImageVariant.fromRequestValue(variant);
        byte[] zipBytes = deploymentPackageService.buildDeploymentZip(
                node,
                imageReference,
                imageVariant
        );
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
            String nodeIp,
            String externalUrl,
            String apiUrl
    ) {
        public CreateNodeRequest(String nodeId, String nodeName, BlogRegion region,
                                 EdgeConnectionType connectionType, Integer edgeGrpcPort,
                                 String nodeIp) {
            this(nodeId, nodeName, region, connectionType, edgeGrpcPort, nodeIp, null, null);
        }
    }

    @RegisterForReflection
    public record ConnectionBootstrapRequest(
            String nodeId,
            String nodeName,
            BlogRegion region,
            String peerUrl,
            String externalUrl,
            String tenantId,
            String datasetId,
            String authToken,
            String incarnation
    ) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private int generateRandomHighPort() {
        return ThreadLocalRandom.current().nextInt(20000, 60000);
    }
}
