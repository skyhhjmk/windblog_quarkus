package com.biliwind.blog.service.edge;

import com.biliwind.blog.model.EdgeNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@ApplicationScoped
public class EdgeNodeStatusService {

    @Transactional
    public void markPersistentChannelOnline(String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            return;
        }

        node.status = "ONLINE";
        node.lastHeartbeat = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Transactional
    public void markPersistentChannelOffline(String nodeId) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            return;
        }

        node.status = "OFFLINE";
    }
}
