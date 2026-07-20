package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EmailChannel;
import com.biliwind.blog.service.storage.StorageConfigProtector;
import com.biliwind.blog.service.EmailDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

@Path("/api/admin/email-channels")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminEmailChannelController {
    @Inject
    ObjectMapper objectMapper;
    @Inject
    StorageConfigProtector storageConfigProtector;
    @Inject
    EmailDeliveryService emailDeliveryService;

    @GET
    public List<EmailChannelResponse> list() {
        List<EmailChannelResponse> responses = new ArrayList<>();
        List<EmailChannel> channels = EmailChannel.list("order by id");
        for (EmailChannel channel : channels) responses.add(toResponse(channel));
        return responses;
    }

    @POST
    @Transactional
    public EmailChannelResponse create(EmailChannelRequest request) {
        EmailChannel channel = new EmailChannel();
        applyRequest(channel, request);
        channel.persist();
        return toResponse(channel);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public EmailChannelResponse update(@PathParam("id") Long id, EmailChannelRequest request) {
        EmailChannel channel = EmailChannel.findById(id);
        if (channel == null) throw new WebApplicationException("邮件通道不存在", Response.Status.NOT_FOUND);
        applyRequest(channel, request);
        return toResponse(channel);
    }

    @POST
    @Path("/{id}/test")
    @Transactional
    public void sendTest(@PathParam("id") Long id, TestRequest request) {
        EmailChannel channel = EmailChannel.findById(id);
        if (channel == null) {
            throw new WebApplicationException("邮件通道不存在", Response.Status.NOT_FOUND);
        }
        if (request == null || request.recipientAddress == null || request.recipientAddress.isBlank()) {
            throw new WebApplicationException("测试收件人不能为空", Response.Status.BAD_REQUEST);
        }
        String html = "<h1>SMTP 通道测试成功入队</h1><p>此邮件用于验证通道配置。</p>";
        emailDeliveryService.queueWithRoute("CHANNEL_TEST", request.recipientAddress, "WindBlog SMTP 通道测试", html, null, channel.id);
    }

    private void applyRequest(EmailChannel channel, EmailChannelRequest request) {
        if (request == null || request.name == null || request.name.isBlank() || request.host == null || request.host.isBlank()) {
            throw new WebApplicationException("通道名称和 SMTP 主机不能为空", Response.Status.BAD_REQUEST);
        }
        if (!"STARTTLS".equals(request.securityMode) && !"SSL_TLS".equals(request.securityMode) && !"NONE".equals(request.securityMode)) {
            throw new WebApplicationException("不支持的加密模式", Response.Status.BAD_REQUEST);
        }
        channel.name = request.name.trim();
        channel.provider = request.provider;
        channel.host = request.host.trim();
        channel.port = request.port;
        channel.securityMode = request.securityMode;
        channel.username = request.username;
        channel.fromName = request.fromName;
        channel.fromAddress = request.fromAddress;
        channel.replyToAddress = request.replyToAddress;
        channel.enabled = request.enabled;
        if (request.password != null && !request.password.isBlank()) {
            try {
                String configJson = objectMapper.createObjectNode().put("password", request.password).toString();
                channel.passwordEncrypted = storageConfigProtector.protectForStorage(configJson);
            } catch (Exception exception) {
                throw new IllegalStateException("邮件密码加密失败", exception);
            }
        }
    }

    private EmailChannelResponse toResponse(EmailChannel channel) {
        String maskedPassword = channel.passwordEncrypted == null ? "" : "******";
        return new EmailChannelResponse(channel.id, channel.name, channel.provider, channel.host, channel.port,
                channel.securityMode, channel.username, maskedPassword, channel.fromName, channel.fromAddress,
                channel.replyToAddress, channel.enabled);
    }

    public static class EmailChannelRequest {
        public String name;
        public String provider;
        public String host;
        public int port;
        public String securityMode;
        public String username;
        public String password;
        public String fromName;
        public String fromAddress;
        public String replyToAddress;
        public boolean enabled;
    }

    public static class TestRequest {
        public String recipientAddress;
    }

    public record EmailChannelResponse(Long id, String name, String provider, String host, int port,
                                       String securityMode, String username, String password, String fromName,
                                       String fromAddress,
                                       String replyToAddress, boolean enabled) {
    }
}
