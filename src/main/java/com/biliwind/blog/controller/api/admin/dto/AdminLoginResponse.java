package com.biliwind.blog.controller.api.admin.dto;

public record AdminLoginResponse(
        boolean success,
        String token,
        String tokenType,
        long expiresAtEpochSeconds,
        AdminUserProfile user
) {
}
