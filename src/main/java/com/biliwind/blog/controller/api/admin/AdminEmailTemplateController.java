package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EmailTemplate;
import com.biliwind.blog.service.EmailDeliveryService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.List;

@Path("/api/admin/email-templates")
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
public class AdminEmailTemplateController {
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;
    @Inject
    EmailDeliveryService emailDeliveryService;

    @GET
    public List<EmailTemplate> list() {
        return EmailTemplate.list("order by updatedAt desc");
    }

    @POST
    @Transactional
    public EmailTemplate create(EmailTemplate request) {
        validate(request);
        request.id = null;
        request.version = 1;
        request.updatedAt = OffsetDateTime.now();
        request.persist();
        return request;
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public EmailTemplate update(@PathParam("id") Long id, EmailTemplate request) {
        EmailTemplate template = EmailTemplate.findById(id);
        if (template == null) throw new WebApplicationException("邮件模板不存在", Response.Status.NOT_FOUND);
        validate(request);
        template.name = request.name;
        template.subjectTemplate = request.subjectTemplate;
        template.title = request.title;
        template.greeting = request.greeting;
        template.content = request.content;
        template.buttonText = request.buttonText;
        template.buttonUrl = request.buttonUrl;
        template.published = request.published;
        template.version = template.version + 1;
        template.updatedAt = OffsetDateTime.now();
        return template;
    }

    @GET
    @Path("/{id}/preview")
    public String preview(@PathParam("id") Long id) {
        EmailTemplate template = EmailTemplate.findById(id);
        if (template == null) throw new WebApplicationException("邮件模板不存在", Response.Status.NOT_FOUND);
        return emailTemplateRenderer.render(template.title, template.greeting, template.content, template.buttonText, template.buttonUrl);
    }

    @POST
    @Path("/{id}/test")
    @Transactional
    public void sendTest(@PathParam("id") Long id, TestRequest request) {
        EmailTemplate template = EmailTemplate.findById(id);
        if (template == null || !template.published)
            throw new WebApplicationException("请先发布邮件模板", Response.Status.BAD_REQUEST);
        emailDeliveryService.queue("TEMPLATE_TEST", request.recipientAddress, template.subjectTemplate, emailTemplateRenderer.render(template.title, template.greeting, template.content, template.buttonText, template.buttonUrl));
    }

    private void validate(EmailTemplate template) {
        if (template == null || template.templateKey == null || template.templateKey.isBlank() || template.name == null || template.name.isBlank())
            throw new WebApplicationException("模板标识和名称不能为空", Response.Status.BAD_REQUEST);
    }

    public static class TestRequest {
        public String recipientAddress;
    }
}
