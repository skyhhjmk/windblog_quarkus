package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.edge.EdgeNodeInfo;
import com.biliwind.blog.service.edge.EdgeNodeRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/admin/storage/edge-nodes")
@Produces(MediaType.APPLICATION_JSON)
public class AdminEdgeNodeController {

    @Inject
    EdgeNodeRegistry registry;

    @GET
    public List<EdgeNodeInfo> listNodes() {
        return registry.getAllNodes();
    }
}
