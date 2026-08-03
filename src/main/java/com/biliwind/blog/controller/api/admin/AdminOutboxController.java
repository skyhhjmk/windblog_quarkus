package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.OutboxEvent;
import com.biliwind.blog.service.AuditService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Safe operational view and controlled replay for persisted outbox events. */
@Path("/api/admin/outbox")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AdminOutbox")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminOutboxController {

    @Inject
    AuditService auditService;

    @GET
    @Transactional
    @Operation(summary = "查询 outbox 事件")
    public PageResult<OutboxEventView> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("status") String status,
            @QueryParam("eventType") String eventType,
            @QueryParam("traceId") String traceId) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        appendFilter(query, params, "status", status);
        appendFilter(query, params, "eventType", eventType);
        appendFilter(query, params, "traceId", traceId);

        PanacheQuery<OutboxEvent> events = OutboxEvent.find(
                query.toString(), Sort.by("createdAt").descending(), params);
        List<OutboxEventView> views = new ArrayList<>();
        for (OutboxEvent event : events.page(Page.of(safePage - 1, safePageSize)).list()) {
            views.add(toView(event));
        }
        return new PageResult<>(views, events.count(), safePage, safePageSize);
    }

    @GET
    @Path("/{id}")
    @Transactional
    @Operation(summary = "查看 outbox 事件")
    public Response get(@PathParam("id") Long id) {
        OutboxEvent event = OutboxEvent.findById(id);
        if (event == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "outbox 事件不存在"))
                    .build();
        }
        return Response.ok(Map.of("success", true, "data", toView(event))).build();
    }

    @POST
    @Path("/{id}/replay")
    @Transactional
    @Operation(summary = "重放失败的 outbox 事件")
    public Response replay(@PathParam("id") Long id) {
        OutboxEvent event = OutboxEvent.findById(id);
        if (event == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "outbox 事件不存在"))
                    .build();
        }
        if (!"FAILED".equals(event.status)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "只有 FAILED 事件允许重放",
                            "status", event.status == null ? "" : event.status))
                    .build();
        }

        String oldStatus = event.status;
        event.status = "PENDING";
        event.attemptCount = 0;
        event.availableAt = OffsetDateTime.now();
        event.lockedUntil = null;
        event.lockOwner = null;
        event.lastError = null;
        event.publishedAt = null;
        event.persist();

        auditService.log("outbox_event", event.id, "replay",
                Map.of("status", oldStatus),
                Map.of("status", event.status),
                Map.of("eventType", safe(event.eventType), "traceId", safe(event.traceId)));

        return Response.ok(Map.of("success", true, "data", toView(event))).build();
    }

    private void appendFilter(StringBuilder query, Map<String, Object> params,
                              String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        query.append(" and ").append(field).append(" = :").append(field);
        params.put(field, value.trim());
    }

    private OutboxEventView toView(OutboxEvent event) {
        return new OutboxEventView(
                event.id,
                event.eventKey,
                event.eventType,
                event.aggregateType,
                event.aggregateId,
                event.status,
                event.attemptCount,
                event.availableAt,
                event.lockedUntil,
                SensitiveMessageSanitizer.sanitize(event.lastError),
                event.traceId,
                event.createdAt,
                event.publishedAt);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record OutboxEventView(
            Long id,
            String eventKey,
            String eventType,
            String aggregateType,
            String aggregateId,
            String status,
            int attemptCount,
            OffsetDateTime availableAt,
            OffsetDateTime lockedUntil,
            String lastError,
            String traceId,
            OffsetDateTime createdAt,
            OffsetDateTime publishedAt) {
    }
}
