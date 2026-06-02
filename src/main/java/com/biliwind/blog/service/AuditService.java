package com.biliwind.blog.service;

import com.biliwind.blog.context.AdminAuditRequestContext;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.AuditLog;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Map;

@ApplicationScoped
public class AuditService {

    @Inject
    Instance<AdminRequestContext> adminRequestContextInstance;

    @Inject
    Instance<AdminAuditRequestContext> adminAuditRequestContextInstance;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void log(String entityType, Object entityId, String action, Object oldValue, Object newValue) {
        log(entityType, entityId, action, oldValue, newValue, null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void log(String entityType, Object entityId, String action, Object oldValue, Object newValue, Map<String, Object> extInfo) {
        Long userId = resolveCurrentUserId();
        log(entityType, entityId, action, oldValue, newValue, extInfo, userId);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void log(String entityType, Object entityId, String action, Object oldValue, Object newValue, Map<String, Object> extInfo, Long userId) {
        AuditLog auditLog = new AuditLog();
        auditLog.entityType = entityType;
        if (entityId != null) {
            auditLog.entityId = String.valueOf(entityId);
        }
        auditLog.action = action;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.extInfo = extInfo;
        copyRequestContext(auditLog);

        if (userId != null) {
            auditLog.performedBy = User.findById(userId);
        }

        auditLog.persist();
    }

    private void copyRequestContext(AuditLog auditLog) {
        AdminAuditRequestContext auditRequestContext = resolveAuditRequestContext();
        if (auditRequestContext == null) {
            return;
        }

        if (auditRequestContext.getRequestId() != null && !auditRequestContext.getRequestId().isBlank()) {
            auditLog.requestId = auditRequestContext.getRequestId();
        }

        if (auditRequestContext.getRequestMethod() != null && !auditRequestContext.getRequestMethod().isBlank()) {
            auditLog.requestMethod = auditRequestContext.getRequestMethod();
        }

        if (auditRequestContext.getRequestPath() != null && !auditRequestContext.getRequestPath().isBlank()) {
            auditLog.requestPath = auditRequestContext.getRequestPath();
        }

        if (auditRequestContext.getClientIp() != null && !auditRequestContext.getClientIp().isBlank()) {
            auditLog.clientIp = auditRequestContext.getClientIp();
        }

        if (auditRequestContext.getUserAgent() != null && !auditRequestContext.getUserAgent().isBlank()) {
            auditLog.userAgent = auditRequestContext.getUserAgent();
        }
    }

    private Long resolveCurrentUserId() {
        AdminRequestContext adminRequestContext = resolveAdminRequestContext();
        if (adminRequestContext == null) {
            return null;
        }
        return adminRequestContext.getUserId();
    }

    private AdminRequestContext resolveAdminRequestContext() {
        if (adminRequestContextInstance == null) {
            return null;
        }
        try {
            return adminRequestContextInstance.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private AdminAuditRequestContext resolveAuditRequestContext() {
        if (adminAuditRequestContextInstance == null) {
            return null;
        }
        try {
            return adminAuditRequestContextInstance.get();
        } catch (Exception ignored) {
            return null;
        }
    }
}
