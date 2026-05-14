package com.biliwind.blog.service.edge;

/**
 * 通用数据同步事件
 *
 * @param entityType 实体类型 (如: TAG, CATEGORY)
 * @param entityId   实体ID
 * @param action     操作类型 (UPSERT, DELETE)
 */
public record DataSyncEvent(String entityType, Long entityId, String action) {
}
