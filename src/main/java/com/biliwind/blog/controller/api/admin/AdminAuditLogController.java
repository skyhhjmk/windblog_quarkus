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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
            @QueryParam("action") String action,
            @QueryParam("requestId") String requestId) {

        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (!isBlank(entityType)) {
            query.append(" and entityType = :entityType");
            params.put("entityType", entityType.trim());
        }

        if (!isBlank(action)) {
            query.append(" and action = :action");
            params.put("action", action.trim());
        }

        if (!isBlank(requestId)) {
            query.append(" and requestId = :requestId");
            params.put("requestId", requestId.trim());
        }

        PanacheQuery<AuditLog> panacheQuery = AuditLog.find(
                query.toString(),
                Sort.by("createdAt").descending(),
                params
        );

        List<AuditLog> logs = panacheQuery.page(Page.of(safePage - 1, safePageSize)).list();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分ss秒SSS毫秒");
        List<AuditLogItem> items = new ArrayList<>();
        for (AuditLog log : logs) {
            items.add(buildAuditLogItem(log, formatter));
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
            String requestId,
            String requestMethod,
            String requestPath,
            String clientIp,
            String userAgent,
            OffsetDateTime createdAt,
            String createdAtFormatted
    ) {
    }

    private AuditLogItem buildAuditLogItem(AuditLog log, DateTimeFormatter formatter) {
        String formattedTime = "";
        if (log.createdAt != null) {
            formattedTime = log.createdAt.atZoneSameInstant(ZoneId.systemDefault()).format(formatter);
        }

        Long performedById = null;
        String performedByUsername = null;
        if (log.performedBy != null) {
            performedById = log.performedBy.id;
            performedByUsername = log.performedBy.username;
        }

        return new AuditLogItem(
                log.id,
                log.entityType,
                log.entityId,
                log.action,
                log.oldValue,
                log.newValue,
                log.extInfo,
                performedById,
                performedByUsername,
                log.requestId,
                log.requestMethod,
                log.requestPath,
                log.clientIp,
                log.userAgent,
                log.createdAt,
                formattedTime
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
