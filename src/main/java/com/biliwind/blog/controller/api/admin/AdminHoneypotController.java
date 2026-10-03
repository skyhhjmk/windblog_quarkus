package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.HoneypotEvent;
import com.biliwind.blog.model.HoneypotEventSample;
import com.biliwind.blog.service.security.HoneypotService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/security/honeypot")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("SUPER_ADMIN")
public class AdminHoneypotController {

    @Inject
    HoneypotService honeypotService;

    @Inject
    EntityManager entityManager;

    @GET
    @Path("/rules")
    public Map<String, Object> rules() {
        return Map.of("items", honeypotService.listRules());
    }

    @PUT
    @Path("/rules/{ruleKey}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateRule(@PathParam("ruleKey") String ruleKey, RuleUpdateRequest request) {
        if (request == null || request.enabled() == null || request.action() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "必须提供 enabled 和 action"))
                    .build();
        }
        try {
            return Response.ok(Map.of("data", honeypotService.updateRule(
                    ruleKey, request.enabled(), request.action()))).build();
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", exception.getMessage())).build();
        }
    }

    @GET
    @Path("/stats")
    @Transactional
    public HoneypotStats stats(@QueryParam("from") String from, @QueryParam("to") String to) {
        TimeRange range = timeRange(from, to);
        long total = ((Number) entityManager.createNativeQuery(
                        "select count(*) from honeypot_events where created_at >= :from and created_at <= :to")
                .setParameter("from", range.from()).setParameter("to", range.to()).getSingleResult()).longValue();
        long blocked = ((Number) entityManager.createNativeQuery(
                        "select count(*) from honeypot_events where action = 'BLOCK' and created_at >= :from and created_at <= :to")
                .setParameter("from", range.from()).setParameter("to", range.to()).getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> ruleRows = entityManager.createNativeQuery(
                        "select r.rule_key, count(*) from honeypot_events e "
                                + "cross join lateral jsonb_array_elements_text(e.matched_rules) r(rule_key) "
                                + "where e.created_at >= :from and e.created_at <= :to "
                                + "group by r.rule_key order by count(*) desc")
                .setParameter("from", range.from()).setParameter("to", range.to()).getResultList();
        List<RuleCount> byRule = new ArrayList<>();
        for (Object[] row : ruleRows) byRule.add(new RuleCount(row[0].toString(), ((Number) row[1]).longValue()));

        @SuppressWarnings("unchecked")
        List<Object[]> trendRows = entityManager.createNativeQuery(
                        "select date_trunc('day', created_at), count(*) from honeypot_events "
                                + "where created_at >= :from and created_at <= :to "
                                + "group by date_trunc('day', created_at) order by 1")
                .setParameter("from", range.from()).setParameter("to", range.to()).getResultList();
        List<TrendPoint> trend = new ArrayList<>();
        for (Object[] row : trendRows) trend.add(new TrendPoint(row[0].toString(), ((Number) row[1]).longValue()));
        return new HoneypotStats(total, blocked, total - blocked, byRule, trend, range.from(), range.to());
    }

    @GET
    @Path("/events")
    @Transactional
    public EventPage events(
            @DefaultValue("1") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("pageSize") int pageSize,
            @QueryParam("ruleKey") String ruleKey,
            @QueryParam("action") String action,
            @QueryParam("clientIp") String clientIp,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        TimeRange range = timeRange(from, to);
        if (notBlank(ruleKey) && honeypotService.listRules().stream()
                .noneMatch(rule -> rule.key().equals(ruleKey.trim()))) {
            return new EventPage(List.of(), 0, safePage, safeSize);
        }
        if (notBlank(action) && !"OBSERVE".equalsIgnoreCase(action.trim())
                && !"BLOCK".equalsIgnoreCase(action.trim())) {
            return new EventPage(List.of(), 0, safePage, safeSize);
        }
        StringBuilder where = new StringBuilder("created_at >= :from and created_at <= :to");
        if (notBlank(ruleKey)) where.append(" and matched_rules @> cast(:ruleJson as jsonb)");
        if (notBlank(action)) where.append(" and action = :action");
        if (notBlank(clientIp)) where.append(" and client_ip = :clientIp");

        var countQuery = entityManager.createNativeQuery("select count(*) from honeypot_events where " + where);
        var selectQuery = entityManager.createNativeQuery(
                "select * from honeypot_events where " + where + " order by created_at desc, id desc",
                HoneypotEvent.class);
        countQuery.setParameter("from", range.from()).setParameter("to", range.to());
        selectQuery.setParameter("from", range.from()).setParameter("to", range.to());
        if (notBlank(ruleKey)) {
            String ruleJson = "[\"" + ruleKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
            countQuery.setParameter("ruleJson", ruleJson);
            selectQuery.setParameter("ruleJson", ruleJson);
        }
        if (notBlank(action)) {
            countQuery.setParameter("action", action.trim().toUpperCase(java.util.Locale.ROOT));
            selectQuery.setParameter("action", action.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (notBlank(clientIp)) {
            countQuery.setParameter("clientIp", clientIp.trim());
            selectQuery.setParameter("clientIp", clientIp.trim());
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<HoneypotEvent> entities = selectQuery.setFirstResult((safePage - 1) * safeSize)
                .setMaxResults(safeSize).getResultList();
        return new EventPage(entities.stream().map(AdminHoneypotController::toSummary).toList(),
                total, safePage, safeSize);
    }

    @GET
    @Path("/events/{id}")
    @Transactional
    public Response eventDetail(@PathParam("id") long id) {
        HoneypotEvent event = HoneypotEvent.findById(id);
        if (event == null) return Response.status(Response.Status.NOT_FOUND).build();
        HoneypotEventSample sample = HoneypotEventSample.findById(id);
        return Response.ok(new EventDetail(toSummary(event),
                sample == null ? Map.of() : sample.requestHeaders,
                sample == null || sample.bodySample == null
                        ? "" : Base64.getEncoder().encodeToString(sample.bodySample))).build();
    }

    private static EventSummary toSummary(HoneypotEvent event) {
        return new EventSummary(event.id, event.clientIp, event.method, event.requestUri, event.userAgent,
                event.matchedRules, event.action, event.bodyTruncated,
                event.bodyBytes, event.createdAt);
    }

    private TimeRange timeRange(String from, String to) {
        OffsetDateTime end = parseTime(to, OffsetDateTime.now());
        OffsetDateTime start = parseTime(from, end.minusDays(7));
        if (start.isAfter(end)) start = end.minusDays(7);
        return new TimeRange(start, end);
    }

    private OffsetDateTime parseTime(String raw, OffsetDateTime fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return OffsetDateTime.parse(raw); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }

    @RegisterForReflection
    public record RuleUpdateRequest(Boolean enabled, String action) { }
    @RegisterForReflection
    public record RuleCount(String ruleKey, long count) { }
    @RegisterForReflection
    public record TrendPoint(String day, long count) { }
    @RegisterForReflection
    public record HoneypotStats(long total, long blocked, long observed, List<RuleCount> byRule,
                                List<TrendPoint> trend, OffsetDateTime from, OffsetDateTime to) { }
    @RegisterForReflection
    public record EventSummary(long id, String clientIp, String method, String requestUri, String userAgent,
                               List<String> matchedRules, String action, boolean bodyTruncated,
                               int bodyBytes, OffsetDateTime createdAt) { }
    @RegisterForReflection
    public record EventDetail(EventSummary event, Map<String, List<String>> requestHeaders, String bodySampleBase64) { }
    @RegisterForReflection
    public record EventPage(List<EventSummary> items, long total, int page, int pageSize) { }
    private record TimeRange(OffsetDateTime from, OffsetDateTime to) { }
}
