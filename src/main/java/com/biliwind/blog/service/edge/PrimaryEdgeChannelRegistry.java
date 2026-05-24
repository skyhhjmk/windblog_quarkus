package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.EdgeChannelMessage;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PrimaryEdgeChannelRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrimaryEdgeChannelRegistry.class);
    private final Map<String, ChannelEntry> channelEntries = new ConcurrentHashMap<>();
    @Inject
    EdgeNodeStatusService edgeNodeStatusService;

    public void register(String nodeId, MultiEmitter<? super EdgeChannelMessage> emitter) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        ChannelEntry previousEntry = channelEntries.remove(nodeId);
        if (previousEntry != null) {
            previousEntry.close();
        }

        ChannelEntry nextEntry = new ChannelEntry(nodeId, emitter);
        channelEntries.put(nodeId, nextEntry);
        edgeNodeStatusService.markPersistentChannelOnline(nodeId);
        LOGGER.info("边缘节点持久通道已建立: {}", nodeId);
    }

    public void unregister(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        ChannelEntry removedEntry = channelEntries.remove(nodeId);
        if (removedEntry != null) {
            removedEntry.close();
            edgeNodeStatusService.markPersistentChannelOffline(nodeId);
            LOGGER.info("边缘节点持久通道已关闭: {}", nodeId);
        }
    }

    public void unregister(String nodeId, MultiEmitter<? super EdgeChannelMessage> emitter) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        if (emitter == null) {
            return;
        }

        ChannelEntry currentEntry = channelEntries.get(nodeId);
        if (currentEntry == null) {
            return;
        }
        if (currentEntry.emitter != emitter) {
            LOGGER.debug("忽略过期持久通道关闭事件: {}", nodeId);
            return;
        }

        unregister(nodeId);
    }

    public boolean sendToNode(String nodeId, EdgeChannelMessage message) {
        ChannelEntry entry = channelEntries.get(nodeId);
        if (entry == null) {
            return false;
        }

        return entry.send(message);
    }

    public int broadcast(EdgeChannelMessage message) {
        int sentCount = 0;
        List<String> failedNodeIds = new ArrayList<>();

        for (ChannelEntry entry : channelEntries.values()) {
            boolean sent = entry.send(message);
            if (sent) {
                sentCount = sentCount + 1;
            } else {
                failedNodeIds.add(entry.nodeId);
            }
        }

        for (String failedNodeId : failedNodeIds) {
            unregister(failedNodeId);
        }

        return sentCount;
    }

    public boolean hasOnlineChannel(String nodeId) {
        return channelEntries.containsKey(nodeId);
    }

    public OffsetDateTime getConnectedAt(String nodeId) {
        ChannelEntry entry = channelEntries.get(nodeId);
        if (entry == null) {
            return null;
        }
        return entry.connectedAt;
    }

    private static class ChannelEntry {
        private final String nodeId;
        private final MultiEmitter<? super EdgeChannelMessage> emitter;
        private final OffsetDateTime connectedAt;

        ChannelEntry(String nodeId, MultiEmitter<? super EdgeChannelMessage> emitter) {
            this.nodeId = nodeId;
            this.emitter = emitter;
            this.connectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }

        boolean send(EdgeChannelMessage message) {
            try {
                emitter.emit(message);
                return true;
            } catch (Exception exception) {
                LOGGER.error("向边缘节点发送通道消息失败: {}", nodeId, exception);
                return false;
            }
        }

        void close() {
            try {
                emitter.complete();
            } catch (Exception exception) {
                LOGGER.debug("关闭边缘节点通道失败: {}, connectedAt={}", nodeId, connectedAt, exception);
            }
        }
    }
}
