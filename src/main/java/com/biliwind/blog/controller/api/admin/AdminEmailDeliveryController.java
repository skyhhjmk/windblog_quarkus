package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EmailDelivery;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.List;

@Path("/api/admin/email-deliveries")
@Produces(MediaType.APPLICATION_JSON)
public class AdminEmailDeliveryController {
    @GET
    public List<EmailDelivery> list() {
        return EmailDelivery.list("order by createdAt desc");
    }

    @POST
    @Path("/{id}/retry")
    @Transactional
    public EmailDelivery retry(@PathParam("id") Long id) {
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
        return delivery;
    }
}
