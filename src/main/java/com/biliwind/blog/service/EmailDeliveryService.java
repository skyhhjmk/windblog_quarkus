package com.biliwind.blog.service;

import com.biliwind.blog.model.*;
import com.biliwind.blog.service.storage.StorageConfigProtector;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

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

    @Inject
    Instance<EmailDeliveryService> self;

    @Inject
    Instance<OutboxEventService> outboxEventService;

    @ConfigProperty(name = "windblog.outbox.enabled", defaultValue = "true")
    boolean outboxEnabled;

    @ConfigProperty(name = "windblog.mail.max-deliveries-per-cycle", defaultValue = "100")
    int maxDeliveriesPerCycle;

    @ConfigProperty(name = "windblog.mail.delivery.lease-duration", defaultValue = "5M")
    java.time.Duration deliveryLeaseDuration;

    @ConfigProperty(name = "windblog.mail.max-pending-deliveries", defaultValue = "100000")
    int maxPendingDeliveries;

    @ConfigProperty(name = "windblog.mail.delivery.retention-days", defaultValue = "90")
    int deliveryRetentionDays;

    @ConfigProperty(name = "windblog.mail.smtp.connect-timeout", defaultValue = "10S")
    java.time.Duration smtpConnectTimeout;

    @ConfigProperty(name = "windblog.mail.smtp.read-timeout", defaultValue = "30S")
    java.time.Duration smtpReadTimeout;

    @ConfigProperty(name = "windblog.mail.smtp.write-timeout", defaultValue = "30S")
    java.time.Duration smtpWriteTimeout;

    @Transactional
    public void queue(String scenario, String recipientAddress, String subject, String htmlContent) {
        queueWithRoute(scenario, recipientAddress, subject, htmlContent, null, null);
    }

    @Transactional
    public void queueWithRoute(String scenario, String recipientAddress, String subject, String htmlContent,
                               Long channelGroupId, Long channelId) {
        String resolvedHtml = resolveTemplateHtml(scenario, htmlContent);
        Long resolvedChannelGroupId = channelGroupId;
        Long resolvedChannelId = channelId;
        if (resolvedChannelGroupId == null && resolvedChannelId == null) {
            EmailScenarioRoute route = EmailScenarioRoute.findById(scenario);
            if (route != null) {
                resolvedChannelGroupId = route.channelGroupId;
                resolvedChannelId = route.channelId;
            }
        }
        if (outboxEnabled) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scenario", scenario);
            payload.put("recipientAddress", recipientAddress);
            payload.put("subject", subject);
            payload.put("htmlContent", resolvedHtml);
            payload.put("channelGroupId", resolvedChannelGroupId);
            payload.put("channelId", resolvedChannelId);
            outboxEventService.get().enqueue(
                    "EMAIL_DELIVERY:" + UUID.randomUUID(),
                    "EMAIL_DELIVERY",
                    "EMAIL",
                    recipientAddress,
                    payload,
                    null);
            return;
        }
        self.get().persistQueuedDelivery(scenario, recipientAddress, subject, resolvedHtml,
                resolvedChannelGroupId, resolvedChannelId);
    }

    @Transactional
    public void persistQueuedDelivery(String scenario, String recipientAddress, String subject,
                                     String resolvedHtml, Long channelGroupId, Long channelId) {
        Number pendingCount = (Number) EmailDelivery.getEntityManager().createNativeQuery(
                "select count(*) from email_deliveries where status = 'PENDING'")
                .getSingleResult();
        if (pendingCount.longValue() >= effectiveMaxPendingDeliveries()) {
            throw new IllegalStateException("邮件待发送队列已达到配置上限");
        }
        EmailDelivery delivery = new EmailDelivery();
        delivery.scenario = scenario;
        delivery.recipientAddress = recipientAddress;
        delivery.subject = subject;
        delivery.htmlContent = resolvedHtml;
        delivery.status = "PENDING";
        delivery.createdAt = OffsetDateTime.now();
        delivery.channelGroupId = channelGroupId;
        delivery.channelId = channelId;
        delivery.nextAttemptAt = OffsetDateTime.now();
        delivery.persist();
    }

    @Scheduled(every = "30s")
    public void deliverPendingMessages() {
        if (!enabled) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        int processedDeliveries = 0;
        int cycleLimit = Math.max(1, Math.min(maxDeliveriesPerCycle, 1000));
        while (processedDeliveries < cycleLimit) {
            Long deliveryId = self.get().claimNextDelivery(owner);
            if (deliveryId == null) {
                return;
            }
            processedDeliveries = processedDeliveries + 1;
            EmailDelivery delivery = EmailDelivery.find(
                    "id = ?1 and lockOwner = ?2 and lockedUntil > ?3",
                    deliveryId, owner, OffsetDateTime.now()).firstResult();
            if (delivery == null) {
                continue;
            }
            DeliveryOutcome outcome = sendDelivery(delivery);
            self.get().completeDelivery(delivery.id, owner, outcome);
        }
    }

    @Scheduled(every = "1h", identity = "email-delivery-retention")
    @Transactional
    void purgeRetainedDeliveries() {
        if (deliveryRetentionDays < 1) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(deliveryRetentionDays);
        EmailDelivery.getEntityManager().createNativeQuery(
                        "delete from email_deliveries "
                                + "where status in ('SENT', 'FAILED') and created_at < ?1")
                .setParameter(1, cutoff)
                .executeUpdate();
    }

    @Transactional
    Long claimNextDelivery(String owner) {
        java.time.Duration effectiveLease = deliveryLeaseDuration;
        if (effectiveLease == null || effectiveLease.isNegative() || effectiveLease.isZero()) {
            effectiveLease = java.time.Duration.ofMinutes(2);
        }
        OffsetDateTime leaseUntil = OffsetDateTime.now().plus(effectiveLease);
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) EmailDelivery.getEntityManager().createNativeQuery(
                        "update email_deliveries set locked_until = ?1, lock_owner = ?2 "
                                + "where id = (select id from email_deliveries where status = 'PENDING' "
                                + "and next_attempt_at <= now() and (locked_until is null or locked_until < now()) "
                                + "order by id limit 1 for update skip locked) returning id")
                .setParameter(1, leaseUntil)
                .setParameter(2, owner)
                .getResultList();
        if (ids.isEmpty()) {
            return null;
        }
        return ids.get(0).longValue();
    }

    private DeliveryOutcome sendDelivery(EmailDelivery delivery) {
        EmailChannel channel = null;
        Long channelId = null;
        Transport transport = null;
        try {
            channel = self.get().selectChannel(delivery);
            if (channel == null) {
                return new DeliveryOutcome(null, "没有可用的邮件通道");
            }
            channelId = channel.id;
            Properties properties = new Properties();
            properties.put("mail.smtp.host", channel.host);
            properties.put("mail.smtp.port", String.valueOf(channel.port));
            properties.put("mail.smtp.auth", channel.username != null && !channel.username.isBlank());
            properties.put("mail.smtp.starttls.enable", "STARTTLS".equals(channel.securityMode));
            properties.put("mail.smtp.ssl.enable", "SSL_TLS".equals(channel.securityMode));
            properties.put("mail.smtp.connectiontimeout", String.valueOf(durationMillis(smtpConnectTimeout, 10000L)));
            properties.put("mail.smtp.timeout", String.valueOf(durationMillis(smtpReadTimeout, 30000L)));
            properties.put("mail.smtp.writetimeout", String.valueOf(durationMillis(smtpWriteTimeout, 30000L)));
            Session session = Session.getInstance(properties);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(channel.fromAddress, channel.fromName));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(delivery.recipientAddress, false));
            message.setSubject(delivery.subject, "UTF-8");
            message.setContent(delivery.htmlContent, "text/html; charset=UTF-8");
            transport = session.getTransport("smtp");
            transport.connect(channel.host, channel.port, channel.username, getPassword(channel));
            transport.sendMessage(message, message.getAllRecipients());
            delivery.channelId = channelId;
            return new DeliveryOutcome(channelId, null);
        } catch (Exception exception) {
            return new DeliveryOutcome(channelId, exception.getMessage());
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                    // 发送结果已经确定，关闭连接失败不能阻止租约释放和重试状态落库。
                }
            }
        }
    }

    private long durationMillis(java.time.Duration duration, long fallbackMillis) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return fallbackMillis;
        }
        return Math.max(1000L, duration.toMillis());
    }

    private long effectiveMaxPendingDeliveries() {
        return Math.max(1000L, Math.min(maxPendingDeliveries, 1_000_000L));
    }

    @Transactional
    void completeDelivery(Long deliveryId, String owner, DeliveryOutcome outcome) {
        EmailDelivery delivery = EmailDelivery.find("id = ?1 and lockOwner = ?2", deliveryId, owner).firstResult();
        if (delivery == null) {
            return;
        }
        if (outcome.errorMessage() == null) {
            delivery.channelId = outcome.channelId();
            delivery.status = "SENT";
            delivery.sentAt = OffsetDateTime.now();
            delivery.lockedUntil = null;
            delivery.lockOwner = null;
            return;
        }
        deferDelivery(delivery, outcome.errorMessage());
        delivery.lockedUntil = null;
        delivery.lockOwner = null;
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

    @Transactional
    EmailChannel selectChannel(EmailDelivery delivery) {
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

    private record DeliveryOutcome(Long channelId, String errorMessage) {
    }
}
