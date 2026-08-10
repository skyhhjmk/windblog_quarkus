package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.EmailDelivery;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Path("/api/admin/email-deliveries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminEmailDeliveryController {
    private static final int PAGE_SIZE = 20;
    private static final String MANUAL_FAILURE_REASON = "管理员手动终止未发送邮件";

    @GET
    @Transactional
    public PageResult<EmailDeliveryItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("status") String status) {
        int safePage = Math.max(page, 1);
        StringBuilder query = new StringBuilder("1 = 1");
        Map<String, Object> parameters = new HashMap<>();
        if (!isBlank(status) && !"ALL".equalsIgnoreCase(status.trim())) {
            query.append(" and status = :status");
            parameters.put("status", status.trim().toUpperCase(Locale.ROOT));
        }

        PanacheQuery<EmailDelivery> deliveries = EmailDelivery.find(
                query.toString() + " order by createdAt desc, id desc", parameters);
        long total = deliveries.count();
        int totalPages = total == 0 ? 1 : (int) ((total + PAGE_SIZE - 1) / PAGE_SIZE);
        int effectivePage = Math.min(safePage, totalPages);
        List<EmailDelivery> pageItems = deliveries.page(Page.of(effectivePage - 1, PAGE_SIZE)).list();
        List<EmailDeliveryItem> items = new ArrayList<>();
        for (EmailDelivery delivery : pageItems) {
            items.add(toItem(delivery));
        }
        return new PageResult<>(items, total, effectivePage, PAGE_SIZE);
    }

    @POST
    @Path("/fail-pending")
    @Transactional
    public FailPendingResult failPending() {
        OffsetDateTime now = OffsetDateTime.now();
        long failedCount = EmailDelivery.update(
                "status = 'FAILED', lastError = ?1, nextAttemptAt = ?2, "
                        + "lockedUntil = null, lockOwner = null where status in ('PENDING', 'IN_FLIGHT')",
                MANUAL_FAILURE_REASON, now);
        return new FailPendingResult(failedCount, MANUAL_FAILURE_REASON);
    }

    @POST
    @Path("/{id}/retry")
    @Transactional
    public EmailDeliveryItem retry(@PathParam("id") Long id) {
        EmailDelivery delivery = EmailDelivery.findById(id);
        if (delivery == null) {
            throw new WebApplicationException("邮件投递记录不存在", Response.Status.NOT_FOUND);
        }
        if ("SENT".equals(delivery.status)) {
            throw new WebApplicationException("已发送的邮件不能重复投递", Response.Status.CONFLICT);
        }
        delivery.status = "PENDING";
        delivery.attemptCount = 0;
        delivery.lastError = null;
        delivery.nextAttemptAt = OffsetDateTime.now();
        delivery.lockedUntil = null;
        delivery.lockOwner = null;
        return toItem(delivery);
    }

    private EmailDeliveryItem toItem(EmailDelivery delivery) {
        return new EmailDeliveryItem(
                delivery.id,
                delivery.scenario,
                delivery.recipientAddress,
                delivery.subject,
                delivery.channelId,
                delivery.channelGroupId,
                delivery.status,
                delivery.attemptCount,
                delivery.nextAttemptAt,
                delivery.lastError,
                delivery.createdAt,
                delivery.sentAt,
                delivery.lockedUntil);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record EmailDeliveryItem(
            Long id,
            String scenario,
            String recipientAddress,
            String subject,
            Long channelId,
            Long channelGroupId,
            String status,
            int attemptCount,
            OffsetDateTime nextAttemptAt,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime sentAt,
            OffsetDateTime lockedUntil) {
    }

    public record FailPendingResult(long failedCount, String reason) {
    }
}
