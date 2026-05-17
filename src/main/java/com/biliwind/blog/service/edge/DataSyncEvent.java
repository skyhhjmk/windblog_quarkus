package com.biliwind.blog.service.edge;

/**
 * 通用数据同步事件
 */
public class DataSyncEvent {
    private final String entityType;
    private final Long entityId;
    private final String action;

    public DataSyncEvent(String entityType, Long entityId, String action) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
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
}
