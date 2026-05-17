package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminEdgeNodeDtos.NodeCertificateResponse;
import com.biliwind.blog.controller.api.admin.dto.AdminEdgeNodeDtos.NodeStatusResponse;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.service.security.CertificateService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;

/**
 * 边缘节点证书管理 API
 */
@Path("/api/admin/node/certificate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin Node Certificate", description = "边缘节点证书管理")
public class AdminNodeCertificateController {

    @Inject
    CertificateService certificateService;

    @POST
    @Path("/generate")
    @Transactional
    @Operation(summary = "为节点生成证书对", description = "生成 24h 主证书和 72h 备用证书")
    public NodeCertificateResponse generate(@QueryParam("nodeId") String nodeId) throws Exception {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在: " + nodeId);
        }

        // 签发主证书 (24h)
        CertificateService.GeneratedCertificate primary = certificateService.generateNodeCertificate(nodeId, 24);
        // 签发备用证书 (72h)
        CertificateService.GeneratedCertificate backup = certificateService.generateNodeCertificate(nodeId, 72);

        // 更新数据库记录
        node.certificateSerial = primary.serialNumber();
        node.certificateExpiry = primary.expiry();
        node.certificateRevoked = false;
        node.persist();

        return new NodeCertificateResponse(
                nodeId,
                primary.certificatePem(),
                primary.privateKeyPem(),
                backup.certificatePem(),
                backup.privateKeyPem(),
                primary.caCertificatePem(),
                primary.expiry(),
                backup.expiry()
        );
    }

    @GET
    @Path("/status/{nodeId}")
    @Operation(summary = "获取节点证书状态")
    public NodeStatusResponse getStatus(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        boolean isTrusted = !node.certificateRevoked &&
                node.certificateExpiry != null &&
                node.certificateExpiry.isAfter(OffsetDateTime.now());

        return new NodeStatusResponse(
                nodeId,
                node.status,
                isTrusted,
                node.certificateSerial,
                node.certificateExpiry,
                node.certificateRevoked
        );
    }

    @POST
    @Path("/revoke/{nodeId}")
    @Transactional
    @Operation(summary = "手动吊销节点证书")
    public Response revoke(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }

        node.certificateRevoked = true;
        node.persist();
        return Response.ok().build();
    }
}
