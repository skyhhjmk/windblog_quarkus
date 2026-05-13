package com.biliwind.blog.model;

/**
 * 边缘节点连接模式
 */
public enum EdgeConnectionType {
    /**
     * 心跳模式 (从节点主动连接主节点)
     */
    HEARTBEAT,

    /**
     * 主动连接模式 (主节点主动连接从节点，用于主节点单向可达环境)
     */
    ACTIVE_POLL
}
