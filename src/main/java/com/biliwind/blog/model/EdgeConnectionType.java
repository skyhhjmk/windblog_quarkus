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
    ACTIVE_POLL;

    @com.fasterxml.jackson.annotation.JsonCreator
    public static EdgeConnectionType fromString(String value) {
        if (value == null) return null;
        String normalized = value.toUpperCase().replace("-", "_");
        if (normalized.equals("HEARTBEAT")) return HEARTBEAT;
        if (normalized.equals("ACTIVEPOLL") || normalized.equals("ACTIVE_POLL")) return ACTIVE_POLL;
        // 兼容 Flutter 传来的 lowerCamelCase
        if (value.equals("heartbeat")) return HEARTBEAT;
        if (value.equals("activePoll")) return ACTIVE_POLL;

        return valueOf(normalized);
    }
}
