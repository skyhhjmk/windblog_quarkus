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
    public void log(String entityType, Long entityId, String action, Map<String, Object> oldValue, Map<String, Object> newValue) {
        log(entityType, entityId, action, oldValue, newValue, null, null, null, null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void log(String entityType, Long entityId, String action, Map<String, Object> oldValue, Map<String, Object> newValue,
                    Long durationMs, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        AuditLog auditLog = new AuditLog();
        auditLog.entityType = entityType;
        auditLog.entityId = entityId;
        auditLog.action = action;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.durationMs = durationMs;
        auditLog.inputTokens = inputTokens;
        auditLog.outputTokens = outputTokens;
        auditLog.totalTokens = totalTokens;

        Long userId = adminRequestContext.getUserId();
        if (userId != null) {
            auditLog.performedBy = User.findById(userId);
        }

        auditLog.persist();
    }
}
