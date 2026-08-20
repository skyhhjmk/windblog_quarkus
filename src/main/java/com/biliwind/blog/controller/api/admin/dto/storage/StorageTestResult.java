package com.biliwind.blog.controller.api.admin.dto.storage;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record StorageTestResult(
        boolean success,
        String message
) {
}
