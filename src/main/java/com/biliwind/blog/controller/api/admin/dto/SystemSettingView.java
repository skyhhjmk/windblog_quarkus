package com.biliwind.blog.controller.api.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;

@RegisterForReflection
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
