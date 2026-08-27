package com.biliwind.blog.controller.api.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AdminUserProfile(
        Long id,
        String username,
        String email,
        String roleName
) {
}
