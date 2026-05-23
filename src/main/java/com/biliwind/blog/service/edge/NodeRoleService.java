package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NodeRoleService {

    @ConfigProperty(name = "windblog.node.role", defaultValue = "primary")
    String nodeRole;

    @ConfigProperty(name = "windblog.node.id", defaultValue = "main")
    String nodeId;

    public boolean isPrimaryNode() {
        return resolveNodeRole() == NodeRole.PRIMARY;
    }

    public boolean isEdgeNode() {
        return resolveNodeRole() == NodeRole.EDGE;
    }

    public String getNodeId() {
        if (nodeId == null || nodeId.isBlank()) {
            return "main";
        }
        return nodeId.trim();
    }

    public NodeRole resolveNodeRole() {
        if (nodeRole == null) {
            return NodeRole.PRIMARY;
        }

        String normalizedNodeRole = nodeRole.trim().toUpperCase();
        if ("EDGE".equals(normalizedNodeRole)) {
            return NodeRole.EDGE;
        }
        return NodeRole.PRIMARY;
    }
}
