package com.biliwind.blog.controller.api.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AdminLoginResponse(
        boolean success,
        String token,
        String tokenType,
        long expiresAtEpochSeconds,
        AdminUserProfile user
) {
}
