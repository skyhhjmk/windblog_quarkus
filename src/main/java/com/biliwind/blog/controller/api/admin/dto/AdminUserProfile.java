package com.biliwind.blog.controller.api.admin.dto;

public record AdminUserProfile(
        Long id,
        String username,
        String email,
        String roleName
) {
}
