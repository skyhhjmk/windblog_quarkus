package com.biliwind.blog.controller.api.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

public record SystemSettingView(
        Long id,
        String configKey,
        JsonNode configValue,
        String configType,
        String groupName,
        JsonNode uiSchema,
        String description,
        Integer version,
        boolean isFrozen,
        boolean secret,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
