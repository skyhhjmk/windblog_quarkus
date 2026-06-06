package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class EdgeWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(EdgeWriteGuard.class);

    @Inject
    NodeRoleService nodeRoleService;

    public void rejectWriteOnEdge(String operationName) {
        if (!nodeRoleService.isEdgeNode()) {
            return;
        }
        log.warn("边缘节点拒绝写操作: {}", operationName);
        throw new WebApplicationException("边缘节点处于只读模式，禁止执行写操作: " + operationName,
                Response.Status.SERVICE_UNAVAILABLE);
    }
}
