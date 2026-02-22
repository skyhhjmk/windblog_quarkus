package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class AdminPermissionDtos {

    private AdminPermissionDtos() {
    }

    public record RoleCreateRequest(
            String name,
            String displayName,
            String description,
            boolean canUpload,
            List<String> allowedMimeTypes,
            Long maxSingleUploadBytes,
            Long maxTotalUploadBytes) {
    }

    public record RoleUpdateRequest(
            String displayName,
            String description,
            boolean canUpload,
            List<String> allowedMimeTypes,
            Long maxSingleUploadBytes,
            Long maxTotalUploadBytes) {
    }

    public record PermissionRoleItem(
            String name,
            String displayName,
            String description,
            boolean canUpload,
            List<String> allowedMimeTypes,
            Long maxSingleUploadBytes,
            Long maxTotalUploadBytes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
