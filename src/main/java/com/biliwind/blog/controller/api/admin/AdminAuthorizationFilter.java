package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class AdminAuthorizationFilter implements ContainerRequestFilter {

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    com.biliwind.blog.service.AdminActionSecurityService adminActionSecurityService;

    @Inject
    com.biliwind.blog.service.AdminSecurityAuditService adminSecurityAuditService;

    @Inject
    com.biliwind.blog.service.AdminPermissionService adminPermissionService;

    @Inject
    com.biliwind.blog.service.SecurityMetricsService securityMetricsService;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null || path.isBlank()) {
            return;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.startsWith("/api/admin")) {
            return;
        }
        if (path.endsWith("/auth/login") || path.endsWith("/auth/logout")
                || path.startsWith("/api/admin/docs") || path.startsWith("/api/admin/openapi")) {
            return;
        }

        AdminAuthorizationPolicy.Decision decision = AdminAuthorizationPolicy.decide(
                requestContext.getMethod(), path);
        if (!adminPermissionService.isAllowed(adminRequestContext.getRoleName(), decision.action())) {
            securityMetricsService.increment("admin.denied", "permission");
            adminSecurityAuditService.record(requestContext.getMethod(), path,
                    decision.resource(), decision.action(), "DENIED_PERMISSION");
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("success", false, "message", "缺少管理动作权限",
                            "resource", decision.resource(), "action", decision.action()))
                    .build());
            return;
        }
        if (decision.superAdminOnly() && !adminRequestContext.isSuperAdmin()) {
            securityMetricsService.increment("admin.denied", "super_admin");
            adminSecurityAuditService.record(requestContext.getMethod(), path,
                    decision.resource(), decision.action(), "DENIED_SUPER_ADMIN");
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of(
                            "success", false,
                            "message", "需要超级管理员权限",
                            "resource", decision.resource(),
                            "action", decision.action()))
                    .build());
            return;
        }
        if (decision.stepUpRequired()
                && !adminActionSecurityService.verifyStepUp(
                requestContext.getHeaderString("X-Admin-Step-Up"), adminRequestContext.getUserId())) {
            adminSecurityAuditService.record(requestContext.getMethod(), path,
                    decision.resource(), decision.action(), "DENIED_STEP_UP");
                requestContext.abortWith(Response.status(Response.Status.PRECONDITION_REQUIRED)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("success", false, "message", "该高风险操作需要 5 分钟内的 step-up token",
                            "resource", decision.resource(), "action", decision.action()))
                    .build());
            securityMetricsService.increment("admin.denied", "step_up");
            return;
        }
        if (decision.idempotencyRequired()) {
            String idempotencyKey = requestContext.getHeaderString("Idempotency-Key");
            if (!adminActionSecurityService.reserveIdempotencyKey(
                    adminRequestContext.getUserId(), idempotencyKey, decision.resource(), decision.action())) {
                adminSecurityAuditService.record(requestContext.getMethod(), path,
                        decision.resource(), decision.action(), "DENIED_IDEMPOTENCY");
                requestContext.abortWith(Response.status(Response.Status.CONFLICT)
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .entity(Map.of("success", false, "message", "重复的 Idempotency-Key",
                                "resource", decision.resource(), "action", decision.action()))
                    .build());
                securityMetricsService.increment("admin.denied", "idempotency");
                return;
            }
        }
        if (decision.stepUpRequired()) {
            adminSecurityAuditService.record(requestContext.getMethod(), path,
                    decision.resource(), decision.action(), "AUTHORIZED_HIGH_RISK");
        }
    }
}
