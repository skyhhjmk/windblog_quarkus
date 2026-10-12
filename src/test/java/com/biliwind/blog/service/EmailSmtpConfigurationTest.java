package com.biliwind.blog.service;

import com.biliwind.blog.model.EmailChannel;
import com.biliwind.blog.model.EmailDelivery;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailSmtpConfigurationTest {
    @Test
    void requiresStartTlsAndChecksServerIdentity() {
        var service = new EmailDeliveryService();
        var channel = channel();
        channel.securityMode = "STARTTLS";
        var properties = service.smtpProperties(channel);
        assertEquals(true, properties.get("mail.smtp.starttls.enable"));
        assertEquals(true, properties.get("mail.smtp.starttls.required"));
        assertEquals("true", properties.get("mail.smtp.ssl.checkserveridentity"));
        assertEquals(false, properties.get("mail.smtp.ssl.enable"));
        assertEquals("2525", properties.get("mail.smtp.port"));
        assertEquals("10000", properties.get("mail.smtp.connectiontimeout"));
        channel.securityMode = "SSL_TLS";
        assertEquals(true, service.smtpProperties(channel).get("mail.smtp.ssl.enable"));
        channel.securityMode = "NONE";
        assertEquals(false, service.smtpProperties(channel).get("mail.smtp.starttls.required"));
    }

    @Test
    void includesReplyAddressAndUtf8Content() throws Exception {
        var service = new EmailDeliveryService();
        var channel = channel();
        channel.replyToAddress = "reply@example.com";
        var delivery = new EmailDelivery();
        delivery.recipientAddress = "recipient@example.com";
        delivery.subject = "中文邮件主题";
        delivery.htmlContent = "<p>中文内容</p>";
        var message = service.createMessage(Session.getInstance(service.smtpProperties(channel)), channel, delivery);
        message.saveChanges();
        assertEquals("reply@example.com", ((InternetAddress) message.getReplyTo()[0]).getAddress());
        assertEquals(delivery.subject, message.getSubject());
        assertEquals(delivery.htmlContent, message.getContent());
        assertTrue(message.getContentType().toLowerCase().contains("utf-8"));
    }

    private EmailChannel channel() {
        var channel = new EmailChannel();
        channel.host = "localhost";
        channel.port = 2525;
        channel.securityMode = "NONE";
        channel.fromAddress = "sender@example.com";
        channel.fromName = "WindBlog";
        return channel;
    }
}
