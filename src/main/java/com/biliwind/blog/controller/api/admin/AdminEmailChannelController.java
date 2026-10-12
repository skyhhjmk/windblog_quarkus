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
        ensureUniqueName(channel);
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
        ensureUniqueName(channel);
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
        validateAddress(request.recipientAddress, "测试收件人");
        if (!channel.enabled) throw new WebApplicationException("请先启用邮件通道", Response.Status.BAD_REQUEST);
        if (!emailDeliveryService.isEnabled()) throw new WebApplicationException("邮件投递未启用，请配置 WINDBLOG_MAIL_ENABLED=true", Response.Status.BAD_REQUEST);
        String html = "<h1>SMTP 通道测试成功入队</h1><p>此邮件用于验证通道配置。</p>";
        emailDeliveryService.queueWithRoute("CHANNEL_TEST", request.recipientAddress, "WindBlog SMTP 通道测试", html, null, channel.id);
    }

    private void ensureUniqueName(EmailChannel channel) {
        EmailChannel existing = EmailChannel.find("name", channel.name).firstResult();
        if (existing != null && !existing.id.equals(channel.id)) {
            throw new WebApplicationException("邮件通道名称已存在", Response.Status.CONFLICT);
        }
    }

    void applyRequest(EmailChannel channel, EmailChannelRequest request) {
        if (request == null || request.name == null || request.name.isBlank() || request.host == null || request.host.isBlank()) {
            throw new WebApplicationException("通道名称和 SMTP 主机不能为空", Response.Status.BAD_REQUEST);
        }
        if (!"STARTTLS".equals(request.securityMode) && !"SSL_TLS".equals(request.securityMode) && !"NONE".equals(request.securityMode)) {
            throw new WebApplicationException("不支持的加密模式", Response.Status.BAD_REQUEST);
        }
        if (request.port < 1 || request.port > 65535) {
            throw new WebApplicationException("SMTP 端口必须在 1 到 65535 之间", Response.Status.BAD_REQUEST);
        }
        validateAddress(request.fromAddress, "发件人邮箱");
        if (request.replyToAddress != null && !request.replyToAddress.isBlank()) {
            validateAddress(request.replyToAddress, "回复邮箱");
        }
        if (request.name.trim().length() > 100 || request.host.trim().length() > 255
                || (request.provider != null && request.provider.length() > 32)
                || (request.username != null && request.username.length() > 255)
                || (request.fromName != null && request.fromName.length() > 128)) {
            throw new WebApplicationException("通道配置字段超过长度限制", Response.Status.BAD_REQUEST);
        }
        channel.name = request.name.trim();
        channel.provider = request.provider == null || request.provider.isBlank() ? "CUSTOM_SMTP" : request.provider.trim();
        channel.host = request.host.trim();
        channel.port = request.port;
        channel.securityMode = request.securityMode;
        channel.username = request.username == null ? null : request.username.trim();
        channel.fromName = request.fromName == null || request.fromName.isBlank() ? "WindBlog" : request.fromName.trim();
        channel.fromAddress = request.fromAddress.trim();
        channel.replyToAddress = request.replyToAddress == null || request.replyToAddress.isBlank() ? null : request.replyToAddress.trim();
        channel.enabled = request.enabled;
        if (request.password != null && !request.password.isBlank() && !"******".equals(request.password)) {
            try {
                String configJson = objectMapper.createObjectNode().put("password", request.password).toString();
                channel.passwordEncrypted = storageConfigProtector.protectForStorage(configJson);
            } catch (Exception exception) {
                throw new WebApplicationException("邮件密码加密失败，请配置持久化的 WINDBLOG_STORAGE_CONFIG_SECRET 后重启服务", Response.Status.SERVICE_UNAVAILABLE);
            }
        }
    }

    static void validateAddress(String value, String label) {
        try {
            if (value == null || value.isBlank() || value.trim().length() > 255) throw new IllegalArgumentException();
            jakarta.mail.internet.InternetAddress address = new jakarta.mail.internet.InternetAddress(value.trim(), true);
            address.validate();
            if (!address.getAddress().equals(value.trim()) || !address.getAddress().contains("@")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new WebApplicationException(label + "格式不正确", Response.Status.BAD_REQUEST);
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
