package com.biliwind.blog.service;

import com.biliwind.blog.model.*;
import com.biliwind.blog.service.storage.StorageConfigProtector;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;

@ApplicationScoped
public class EmailDeliveryService {
    @ConfigProperty(name = "windblog.mail.enabled", defaultValue = "false")
    boolean enabled;
    @Inject
    StorageConfigProtector storageConfigProtector;
    @Inject
    ObjectMapper objectMapper;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @Transactional
    public void queue(String scenario, String recipientAddress, String subject, String htmlContent) {
        queueWithRoute(scenario, recipientAddress, subject, htmlContent, null, null);
    }

    @Transactional
    public void queueWithRoute(String scenario, String recipientAddress, String subject, String htmlContent,
                               Long channelGroupId, Long channelId) {
        EmailDelivery delivery = new EmailDelivery();
        delivery.scenario = scenario;
        delivery.recipientAddress = recipientAddress;
        delivery.subject = subject;
        delivery.htmlContent = resolveTemplateHtml(scenario, htmlContent);
        delivery.status = "PENDING";
        delivery.createdAt = OffsetDateTime.now();
        delivery.channelGroupId = channelGroupId;
        delivery.channelId = channelId;
        if (channelGroupId == null && channelId == null) applyScenarioRoute(delivery);
        delivery.nextAttemptAt = OffsetDateTime.now();
        delivery.persist();
    }

    @Scheduled(every = "30s")
    @Transactional
    public void deliverPendingMessages() {
        if (!enabled) {
            return;
        }
        List<EmailDelivery> deliveries = EmailDelivery.list("status = 'PENDING' and nextAttemptAt <= ?1 order by id", OffsetDateTime.now());
        for (EmailDelivery delivery : deliveries) {
            sendDelivery(delivery);
        }
    }

    private void sendDelivery(EmailDelivery delivery) {
        EmailChannel channel = selectChannel(delivery);
        if (channel == null) {
            deferDelivery(delivery, "没有可用的邮件通道");
            return;
        }
        try {
            Properties properties = new Properties();
            properties.put("mail.smtp.host", channel.host);
            properties.put("mail.smtp.port", String.valueOf(channel.port));
            properties.put("mail.smtp.auth", channel.username != null && !channel.username.isBlank());
            properties.put("mail.smtp.starttls.enable", "STARTTLS".equals(channel.securityMode));
            properties.put("mail.smtp.ssl.enable", "SSL_TLS".equals(channel.securityMode));
            Session session = Session.getInstance(properties);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(channel.fromAddress, channel.fromName));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(delivery.recipientAddress, false));
            message.setSubject(delivery.subject, "UTF-8");
            message.setContent(delivery.htmlContent, "text/html; charset=UTF-8");
            Transport transport = session.getTransport("smtp");
            transport.connect(channel.host, channel.port, channel.username, getPassword(channel));
            transport.sendMessage(message, message.getAllRecipients());
            transport.close();
            delivery.channelId = channel.id;
            delivery.status = "SENT";
            delivery.sentAt = OffsetDateTime.now();
        } catch (Exception exception) {
            deferDelivery(delivery, exception.getMessage());
        }
    }

    private void applyScenarioRoute(EmailDelivery delivery) {
        EmailScenarioRoute route = EmailScenarioRoute.findById(delivery.scenario);
        if (route == null) return;
        delivery.channelId = route.channelId;
        delivery.channelGroupId = route.channelGroupId;
    }

    private String resolveTemplateHtml(String scenario, String fallbackHtml) {
        EmailScenarioRoute route = EmailScenarioRoute.findById(scenario);
        if (route == null || route.templateKey == null || route.templateKey.isBlank()) {
            return fallbackHtml;
        }
        EmailTemplate template = EmailTemplate.find("templateKey = ?1 and published = true", route.templateKey).firstResult();
        if (template == null) {
            return fallbackHtml;
        }
        return emailTemplateRenderer.render(template.title, template.greeting, template.content,
                template.buttonText, template.buttonUrl);
    }

    private EmailChannel selectChannel(EmailDelivery delivery) {
        if (delivery.channelId != null)
            return EmailChannel.find("id = ?1 and enabled = true", delivery.channelId).firstResult();
        if (delivery.channelGroupId == null) return EmailChannel.find("enabled = true order by id").firstResult();
        EmailChannelGroup group = EmailChannelGroup.find("id = ?1 and enabled = true", delivery.channelGroupId).firstResult();
        if (group == null) return null;
        List<EmailChannelGroupMember> members = EmailChannelGroupMember.list("groupId = ?1 order by priority", group.id);
        if (members.isEmpty()) return null;
        int selectedIndex = 0;
        if ("ROUND_ROBIN".equals(group.dispatchMode)) {
            selectedIndex = Math.floorMod(group.nextChannelIndex, members.size());
            group.nextChannelIndex = group.nextChannelIndex + 1;
        }
        for (int offset = 0; offset < members.size(); offset++) {
            int memberIndex = Math.floorMod(selectedIndex + offset, members.size());
            EmailChannel channel = EmailChannel.find("id = ?1 and enabled = true", members.get(memberIndex).channelId).firstResult();
            if (channel != null) return channel;
        }
        return null;
    }

    private String getPassword(EmailChannel channel) {
        if (channel.passwordEncrypted == null || channel.passwordEncrypted.isBlank()) return null;
        try {
            String revealedConfig = storageConfigProtector.revealForRuntime(channel.passwordEncrypted);
            return objectMapper.readTree(revealedConfig).path("password").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("邮件密码解密失败", exception);
        }
    }

    private void deferDelivery(EmailDelivery delivery, String errorMessage) {
        delivery.attemptCount = delivery.attemptCount + 1;
        delivery.lastError = errorMessage;
        if (delivery.channelGroupId != null) {
            delivery.channelId = null;
        }
        if (delivery.attemptCount >= 5) {
            delivery.status = "FAILED";
            return;
        }
        delivery.nextAttemptAt = OffsetDateTime.now().plusMinutes(5L * delivery.attemptCount);
    }
}
