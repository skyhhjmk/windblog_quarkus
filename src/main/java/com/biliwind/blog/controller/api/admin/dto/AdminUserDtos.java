package com.biliwind.blog.controller.api.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;

public class AdminUserDtos {

    @RegisterForReflection
    public record AdminUserItem(
            Long id,
            String username,
            String nickname,
            String email,
            String avatar,
            String roleName,
            short status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String lastLoginIp,
            String lastLoginLocation,
            OffsetDateTime lastLoginAt) {
    }

    public record UserUpdateRequest(
            String username,
            String nickname,
            String avatar,
            String email,
            String password,
            Short status,
            String roleName) {
    }

    public record PageResult<T>(
            java.util.List<T> items,
            long total,
            int page,
            int pageSize) {
    }
}
