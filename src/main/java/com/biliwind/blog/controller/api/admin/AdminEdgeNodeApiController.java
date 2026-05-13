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

    @GET
    @Operation(summary = "获取所有边缘节点", description = "获取所有已注册的边缘节点及其状态")
    public List<EdgeNode> list() {
        return registry.getAllNodes();
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
        if (request.address() != null) node.address = request.address();
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

    @DELETE
    @Path("/{nodeId}")
    @Transactional
    @Operation(summary = "删除边缘节点", description = "从系统中移除边缘节点记录")
    public Response delete(@PathParam("nodeId") String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null) {
            node.delete();
        }
        return Response.noContent().build();
    }
}
