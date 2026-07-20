package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EmailCampaign;
import com.biliwind.blog.model.EmailTemplate;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.EmailDeliveryService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.List;

@Path("/api/admin/email-campaigns")
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
public class AdminEmailCampaignController {
    @Inject
    EmailDeliveryService emailDeliveryService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @GET
    public List<EmailCampaign> list() {
        return EmailCampaign.list("order by createdAt desc");
    }

    @POST
    @Transactional
    public EmailCampaign create(CampaignRequest request) {
        if (request.channelId != null && request.channelGroupId != null)
            throw new WebApplicationException("推广任务只能选择通道或通道组", Response.Status.BAD_REQUEST);
        EmailTemplate template = EmailTemplate.findById(request.templateId);
        if (template == null || !template.published)
            throw new WebApplicationException("请选择已发布模板", Response.Status.BAD_REQUEST);
        EmailCampaign campaign = new EmailCampaign();
        campaign.name = request.name;
        campaign.subject = request.subject;
        campaign.templateId = template.id;
        campaign.channelId = request.channelId;
        campaign.channelGroupId = request.channelGroupId;
        campaign.recipientType = request.recipientType;
        campaign.createdAt = OffsetDateTime.now();
        campaign.persist();
        List<User> users = findRecipients(request.recipientType);
        String html = emailTemplateRenderer.render(template.title, template.greeting, template.content, template.buttonText, template.buttonUrl);
        for (User user : users)
            emailDeliveryService.queueWithRoute("PROMOTION", user.email, request.subject, html, request.channelGroupId, request.channelId);
        return campaign;
    }

    private List<User> findRecipients(String recipientType) {
        if ("ARTICLE_UPDATES".equals(recipientType))
            return User.list("emailVerifiedAt is not null and subscribeArticleUpdates = true and status = 1 and deletedAt is null");
        return User.list("emailVerifiedAt is not null and subscribePromotions = true and status = 1 and deletedAt is null");
    }

    public static class CampaignRequest {
        public String name;
        public String subject;
        public Long templateId;
        public Long channelGroupId;
        public Long channelId;
        public String recipientType;
    }
}
