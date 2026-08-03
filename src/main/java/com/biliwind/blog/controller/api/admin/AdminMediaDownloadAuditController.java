package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.MediaDownloadEvent;
import com.biliwind.blog.service.MediaDownloadAuditService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/security/media-download-events")
@Produces(MediaType.APPLICATION_JSON)
public class AdminMediaDownloadAuditController {

    @Inject
    MediaDownloadAuditService auditService;

    @GET
    public Response list(
            @QueryParam("postId") Long postId,
            @QueryParam("mediaId") Long mediaId,
            @QueryParam("ticketId") Long ticketId,
            @QueryParam("userId") Long userId,
            @QueryParam("referrerDomain") String referrerDomain,
            @QueryParam("status") String status,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @DefaultValue("1") @QueryParam("page") int page,
            @DefaultValue("50") @QueryParam("pageSize") int pageSize) {
        StringBuilder queryText = new StringBuilder("1 = 1");
        Parameters parameters = new Parameters();
        if (postId != null) {
            queryText.append(" and postId = :postId");
            parameters.and("postId", postId);
        }
        if (mediaId != null) {
            queryText.append(" and mediaId = :mediaId");
            parameters.and("mediaId", mediaId);
        }
        if (ticketId != null) {
            queryText.append(" and ticketId = :ticketId");
            parameters.and("ticketId", ticketId);
        }
        if (userId != null) {
            queryText.append(" and subjectHash = :subjectHash");
            parameters.and("subjectHash", auditService.hashSubject(userId));
        }
        if (referrerDomain != null && !referrerDomain.isBlank()) {
            queryText.append(" and referrerHash = :referrerHash");
            parameters.and("referrerHash", auditService.hashReferrer(referrerDomain.trim().toLowerCase()));
        }
        if (status != null && !status.isBlank()) {
            queryText.append(" and status = :status");
            parameters.and("status", status.trim().toUpperCase());
        }
        OffsetDateTime fromTime = parseTime(from);
        if (fromTime != null) {
            queryText.append(" and createdAt >= :fromTime");
            parameters.and("fromTime", fromTime);
        }
        OffsetDateTime toTime = parseTime(to);
        if (toTime != null) {
            queryText.append(" and createdAt <= :toTime");
            parameters.and("toTime", toTime);
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        PanacheQuery<MediaDownloadEvent> result = MediaDownloadEvent.find(
                queryText + " order by createdAt desc, id desc", parameters);
        long total = result.count();
        List<MediaDownloadEvent> events = result.page(safePage - 1, safePageSize).list();
        List<Map<String, Object>> items = new ArrayList<>();
        for (MediaDownloadEvent event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.id);
            item.put("mediaId", event.mediaId);
            item.put("postId", event.postId);
            item.put("ticketId", event.ticketId);
            item.put("subjectHash", event.subjectHash);
            item.put("referrerHash", event.referrerHash);
            item.put("bytesSent", event.bytesSent);
            item.put("ticketAgeMillis", event.ticketAgeMillis);
            item.put("status", event.status);
            item.put("denyReason", event.denyReason);
            item.put("nodeId", event.nodeId);
            item.put("createdAt", event.createdAt);
            items.add(item);
        }
        return Response.ok(Map.of("items", items, "total", total,
                "page", safePage, "pageSize", safePageSize)).build();
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
