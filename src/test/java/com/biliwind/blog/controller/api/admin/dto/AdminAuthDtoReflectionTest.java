package com.biliwind.blog.controller.api.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminAuthDtoReflectionTest {

    @Test
    void adminAuthDtosAreRegisteredForNativeReflection() {
        assertNotNull(AdminLoginRequest.class.getAnnotation(RegisterForReflection.class));
        assertNotNull(AdminLoginResponse.class.getAnnotation(RegisterForReflection.class));
        assertNotNull(AdminUserProfile.class.getAnnotation(RegisterForReflection.class));
    }
}
