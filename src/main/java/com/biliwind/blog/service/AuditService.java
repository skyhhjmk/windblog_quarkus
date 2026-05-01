package com.biliwind.blog.service;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.AuditLog;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Map;

@ApplicationScoped
public class AuditService {

    @Inject
    AdminRequestContext adminRequestContext;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void log(String entityType, Object entityId, String action, Object oldValue, Object newValue) {
        log(entityType, entityId, action, oldValue, newValue, null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void log(String entityType, Object entityId, String action, Object oldValue, Object newValue, Map<String, Object> extInfo) {
        AuditLog auditLog = new AuditLog();
        auditLog.entityType = entityType;
        auditLog.entityId = entityId == null ? null : String.valueOf(entityId);
        auditLog.action = action;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.extInfo = extInfo;

        Long userId = adminRequestContext.getUserId();
        if (userId != null) {
            auditLog.performedBy = User.findById(userId);
        }

        auditLog.persist();
    }
}
