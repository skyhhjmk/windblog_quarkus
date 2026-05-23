package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@ApplicationScoped
public class EdgeReadOnlyState {

    @ConfigProperty(name = "windblog.edge.read-only-message", defaultValue = "主节点离线，当前从节点处于只读模式")
    String defaultReadOnlyMessage;

    private volatile boolean primaryOnline;
    private volatile boolean readOnly = true;
    private volatile String currentMessage = "等待连接主节点";
    private volatile OffsetDateTime changedAt = OffsetDateTime.now(ZoneOffset.UTC);

    public boolean isPrimaryOnline() {
        return primaryOnline;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String getCurrentMessage() {
        if (currentMessage == null || currentMessage.isBlank()) {
            return defaultReadOnlyMessage;
        }
        return currentMessage;
    }

    public OffsetDateTime getChangedAt() {
        return changedAt;
    }

    public void markPrimaryOnline() {
        updateState(true, false, "主节点在线");
    }

    public void markPrimaryOffline(String reason) {
        String nextMessage = reason;
        if (nextMessage == null || nextMessage.isBlank()) {
            nextMessage = defaultReadOnlyMessage;
        }
        updateState(false, true, nextMessage);
    }

    private void updateState(boolean nextPrimaryOnline, boolean nextReadOnly, String nextMessage) {
        primaryOnline = nextPrimaryOnline;
        readOnly = nextReadOnly;
        currentMessage = nextMessage;
        changedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
