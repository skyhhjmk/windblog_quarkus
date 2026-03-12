package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;

public class AdminUserDtos {

    public record AdminUserItem(
            Long id,
            String username,
            String email,
            String avatar,
            String nickname,
            String phone,
            String roleName,
            short status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long pointsBalance) {
    }

    public record UserUpdateRequest(
            String email,
            String password,
            String avatar,
            String nickname,
            String phone,
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
