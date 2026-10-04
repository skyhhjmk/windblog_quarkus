package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.User;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class AdminJwtAuthFilter implements ContainerRequestFilter {

    public static final String REQUEST_USER_ID_KEY = "admin.user.id";
    public static final String REQUEST_USERNAME_KEY = "admin.user.name";
    public static final String REQUEST_ROLE_NAME_KEY = "admin.user.role";
    public static final String REQUEST_IS_SUPER_ADMIN_KEY = "admin.user.is_super";

    @Inject
    AdminTokenVerifier tokenVerifier;

    @Inject
    AdminRequestContext adminRequestContext;

    @ConfigProperty(name = "quarkus.swagger-ui.always-include", defaultValue = "false")
    boolean documentationEnabled;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Bypass all OPTIONS requests for CORS preflight
        if (requestContext.getMethod().equalsIgnoreCase("OPTIONS")) {
            return;
        }

        String path = normalizePath(requestContext.getUriInfo().getPath());
        if (!path.startsWith("/api/admin")) {
            return;
        }
        if (path.equals("/api/admin/auth/login") || path.equals("/api/admin/install")
                || path.equals("/api/admin/install/status")) {
            return;
        }
        if (path.startsWith("/api/admin/docs") || path.startsWith("/api/admin/openapi")) {
            if (!documentationEnabled) {
                requestContext.abortWith(Response.status(Response.Status.NOT_FOUND).build());
            }
            return;
        }

        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null) {
            List<String> authList = requestContext.getHeaders().get(HttpHeaders.AUTHORIZATION);
            if (authList != null && !authList.isEmpty()) {
                authHeader = authList.get(0);
            }
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "缺少 Bearer Token", "AUTH_TOKEN_INVALID");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "Token 不能为空", "AUTH_TOKEN_INVALID");
            return;
        }

        AdminTokenVerifier.VerifiedToken verified = tokenVerifier.verify(token);
        if (verified == null) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "登录令牌已失效，请重新登录", "AUTH_TOKEN_INVALID");
            return;
        }
        User currentUser = User.find("id = ?1 and deletedAt is null", verified.uid()).firstResult();
        if (currentUser == null || currentUser.status != 1) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "管理员账号已禁用或不存在", "AUTH_ACCOUNT_INVALID");
            return;
        }
        boolean currentIsAdmin = RoleConstant.ADMIN.equals(currentUser.roleName)
                || RoleConstant.SUPER_ADMIN.equals(currentUser.roleName);
        if (!currentIsAdmin) {
            abort(requestContext, Response.Status.FORBIDDEN, "没有管理员权限", "ADMIN_ROLE_REQUIRED");
            return;
        }
        boolean currentIsSuperAdmin = RoleConstant.SUPER_ADMIN.equals(currentUser.roleName);
        String currentRoleName = currentUser.roleName == null || currentUser.roleName.isBlank()
                ? verified.roleName() : currentUser.roleName;
        SecurityContext originalSecurityContext = requestContext.getSecurityContext();
        adminRequestContext.setUserId(verified.uid());
        adminRequestContext.setUsername(currentUser.username);
        adminRequestContext.setRoleName(currentRoleName);
        adminRequestContext.setIsSuperAdmin(currentIsSuperAdmin);
        requestContext.setProperty(REQUEST_USER_ID_KEY, verified.uid());
        requestContext.setProperty(REQUEST_USERNAME_KEY, currentUser.username);
        requestContext.setProperty(REQUEST_ROLE_NAME_KEY, currentRoleName);
        requestContext.setProperty(REQUEST_IS_SUPER_ADMIN_KEY, currentIsSuperAdmin);

        // Expose the verified principal and role through the Jakarta REST security context.
        requestContext.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> verified.username();
            }

            @Override
            public boolean isUserInRole(String role) {
                // If the user is super admin, allow all roles
                if (currentIsSuperAdmin) return true;
                if (currentRoleName == null) return false;
                return role.equalsIgnoreCase(currentRoleName)
                        || role.equalsIgnoreCase("admin") && currentIsAdmin;
            }

            @Override
            public boolean isSecure() {
                return originalSecurityContext != null && originalSecurityContext.isSecure();
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        });
    }

    private void abort(ContainerRequestContext requestContext, Response.Status status, String message,
                       String code) {
        requestContext.abortWith(Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("success", false, "code", code, "message", message))
                .build());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
    }
}
