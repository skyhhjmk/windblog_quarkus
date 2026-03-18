package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;

public class AdminUserDtos {

    public record AdminUserItem(
            Long id,
            String username,
            String email,
            String avatar,
            String roleName,
            short status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record UserUpdateRequest(
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
