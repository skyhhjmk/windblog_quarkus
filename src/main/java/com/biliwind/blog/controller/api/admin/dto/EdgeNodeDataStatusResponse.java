package com.biliwind.blog.controller.api.admin.dto;

import com.biliwind.blog.service.edge.EdgeNodeAvailabilityService;

import java.time.OffsetDateTime;
import java.util.Map;

public record EdgeNodeDataStatusResponse(
        String nodeId,
        String nodeStatus,
        boolean enabled,
        boolean trusted,
        boolean persistentChannelOnline,
        boolean primaryOnline,
        boolean readOnly,
        String readOnlyMessage,
        OffsetDateTime channelConnectedAt,
        OffsetDateTime lastHeartbeat,
        Map<String, String> metrics,
        EdgeNodeAvailabilityService.AvailabilityRates availability,
        Object syncProgress
) {
}
