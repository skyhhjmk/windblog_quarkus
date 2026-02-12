package com.biliwind.blog.controller.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminLoginRequest(
        @NotBlank(message = "账号不能为空")
        String account,
        @NotBlank(message = "密码不能为空")
        String password
) {
}
