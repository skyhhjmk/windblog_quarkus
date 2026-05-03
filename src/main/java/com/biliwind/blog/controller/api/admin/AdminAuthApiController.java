package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.controller.api.admin.dto.AdminLoginRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminLoginResponse;
import com.biliwind.blog.controller.api.admin.dto.AdminUserProfile;
import com.biliwind.blog.model.User;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Path("/api/admin/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAuth")
public class AdminAuthApiController {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    AdminTokenVerifier tokenVerifier;

    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @ConfigProperty(name = "admin.jwt.secret")
    String jwtSecret;

    @ConfigProperty(name = "admin.jwt.issuer")
    String issuer;

    @ConfigProperty(name = "admin.jwt.expire-minutes")
    long expireMinutes;

    @POST
    @Path("/login")
    @Transactional
    @Operation(summary = "管理员登录", description = "使用账号（用户名或邮箱）和密码登录，返回 JWT Token。")
    @APIResponse(responseCode = "200", description = "登录成功",
            content = @Content(schema = @Schema(implementation = AdminLoginResponse.class)))
    @APIResponse(responseCode = "401", description = "账号或密码错误")
    public Response login(@Valid AdminLoginRequest request) {
        User user = User.find("(username = ?1 or email = ?1) and deletedAt is null", request.account().trim()).firstResult();
        if (user == null || user.status != 1) {
            return unauthorized();
        }

        if (!passwordHasher.matches(request.password(), user.password)) {
            return unauthorized();
        }

        String roleName = user.roleName;
        if (roleName == null || roleName.isBlank()) {
            roleName = RoleConstant.ADMIN;
        }
        boolean isSuperAdmin = RoleConstant.SUPER_ADMIN.equals(roleName);

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(Math.max(1, expireMinutes)));
        String token = Jwt.issuer(issuer)
                .upn(user.username)
                .groups(Set.of("admin"))
                .claim("uid", user.id)
                .claim("is_admin", true)
                .claim("role_name", roleName)
                .claim("is_super_admin", isSuperAdmin)
                .expiresAt(expiresAt)
                .signWithSecret(jwtSecret);

        AdminLoginResponse body = new AdminLoginResponse(
                true,
                token,
                "Bearer",
                expiresAt.getEpochSecond(),
                new AdminUserProfile(user.id, user.username, user.email, roleName)
        );
        return Response.ok(body).build();
    }

    @GET
    @Path("/me")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "当前管理员信息", description = "返回当前已登录管理员信息。")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "401", description = "未登录或 token 无效")
    public Response me() {
        Long userId = adminRequestContext.getUserId();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "未登录"))
                    .build();
        }

        User user = User.find("id = ?1 and deletedAt is null", userId).firstResult();
        if (user == null || user.status != 1) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "用户不存在或已禁用"))
                    .build();
        }

        return Response.ok(Map.of(
                "success", true,
                "user", new AdminUserProfile(user.id, user.username, user.email, user.roleName)
        )).build();
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("success", false, "message", "账号或密码错误"))
                .build();
    }
}
