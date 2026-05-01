package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.AuditLog;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志控制器
 * 已重构为通用结构，支持 extInfo (JSONB) 存储不同业务的扩展字段（如 AI Token）
 */
@Path("/api/admin/audit-logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAuditLog")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminAuditLogController {

    @GET
    @Transactional
    @Operation(summary = "获取审计日志列表")
    public PageResult<AuditLogItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize,
            @QueryParam("entityType") String entityType,
            @QueryParam("action") String action) {

        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (entityType != null && !entityType.isBlank()) {
            query.append(" and entityType = :entityType");
            params.put("entityType", entityType.trim());
        }

        if (action != null && !action.isBlank()) {
            query.append(" and action = :action");
            params.put("action", action.trim());
        }

        PanacheQuery<AuditLog> panacheQuery = AuditLog.find(
                query.toString(),
                Sort.by("createdAt").descending(),
                params
        );

        List<AuditLog> logs = panacheQuery.page(Page.of(safePage - 1, safePageSize)).list();

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分ss秒SSS毫秒");
        List<AuditLogItem> items = new java.util.ArrayList<>();
        for (AuditLog log : logs) {
            String formattedTime = "";
            if (log.createdAt != null) {
                formattedTime = log.createdAt.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(formatter);
            }

            items.add(new AuditLogItem(
                log.id,
                log.entityType,
                    log.entityId, // 现为 String
                log.action,
                log.oldValue,
                log.newValue,
                    log.extInfo, // 新增扩展字段
                log.performedBy != null ? log.performedBy.id : null,
                log.performedBy != null ? log.performedBy.username : null,
                    log.createdAt,
                    formattedTime
            ));
        }

        return new PageResult<>(items, panacheQuery.count(), safePage, safePageSize);
    }

    /**
     * 审计日志展示项
     */
    public record AuditLogItem(
            Long id,
            String entityType,
            String entityId,
            String action,
            Object oldValue,
            Object newValue,
            Object extInfo,
            Long performedById,
            String performedByUsername,
            OffsetDateTime createdAt,
            String createdAtFormatted
    ) {
    }
}
