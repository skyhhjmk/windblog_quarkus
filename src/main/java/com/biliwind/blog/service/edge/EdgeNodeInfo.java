package com.biliwind.blog.service.edge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

public class EdgeNodeInfo {
    private String nodeId;
    private String region;
    private OffsetDateTime lastHeartbeat;
    private Map<String, String> metrics;
    private String status; // ONLINE, OFFLINE

    @JsonProperty("isEnabled")
    private boolean isEnabled;

    public EdgeNodeInfo(String nodeId, String region) {
        this.nodeId = nodeId;
        this.region = region;
        this.status = "ONLINE";
        this.isEnabled = true;
        this.lastHeartbeat = OffsetDateTime.now();
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public OffsetDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(OffsetDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Map<String, String> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, String> metrics) {
        this.metrics = metrics;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }
}
