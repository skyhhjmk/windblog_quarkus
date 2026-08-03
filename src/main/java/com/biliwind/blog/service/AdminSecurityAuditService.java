package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/** Records the authorization boundary without persisting bearer or step-up secrets. */
@ApplicationScoped
public class AdminSecurityAuditService {

    @Inject
    AuditService auditService;

    public void record(String method, String path, String resource, String action, String outcome) {
        auditService.log(
                "admin_authorization",
                path,
                action,
                null,
                outcome,
                Map.of("method", method == null ? "" : method,
                        "resource", resource == null ? "" : resource,
                        "outcome", outcome == null ? "" : outcome));
    }
}
