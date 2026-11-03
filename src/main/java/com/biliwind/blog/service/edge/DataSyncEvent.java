package com.biliwind.blog.service.edge;

/**
 * 通用数据同步事件
 */
public class DataSyncEvent {
    private final String entityType;
    private final Long entityId;
    private final String action;
    private final boolean remoteSync;

    public DataSyncEvent(String entityType, Long entityId, String action) {
        this(entityType, entityId, action, false);
    }

    public DataSyncEvent(String entityType, Long entityId, String action, boolean remoteSync) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.remoteSync = remoteSync;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }

    public boolean isRemoteSync() {
        return remoteSync;
    }
}
