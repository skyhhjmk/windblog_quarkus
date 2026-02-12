package com.biliwind.blog.controller.api.admin;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@OpenAPIDefinition(
        info = @Info(
                title = "WindBlog Admin API",
                version = "1.0.0",
                description = "后台管理接口：鉴权、文章编辑、基础信息",
                contact = @Contact(name = "WindBlog", email = "admin@biliwind.com")
        ),
        tags = {
                @Tag(name = "AdminAuth", description = "后台鉴权接口"),
                @Tag(name = "AdminPost", description = "后台文章管理接口"),
                @Tag(name = "AdminBase", description = "后台基础接口")
        }
)
@SecurityScheme(
        securitySchemeName = "adminBearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class AdminOpenApiConfig {
}
