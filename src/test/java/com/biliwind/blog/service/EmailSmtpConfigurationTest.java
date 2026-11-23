package com.biliwind.blog.service;

import com.biliwind.blog.model.EmailChannel;
import com.biliwind.blog.model.EmailDelivery;
import jakarta.activation.CommandMap;
import jakarta.activation.MailcapCommandMap;
import jakarta.activation.spi.MailcapRegistryProvider;
import jakarta.activation.spi.MimeTypeRegistryProvider;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.StreamProvider;
import org.eclipse.angus.mail.util.MailStreamProvider;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

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
        assertTrue(message.getContentType().toLowerCase().contains("multipart/alternative"));
        var multipart = assertInstanceOf(MimeMultipart.class, message.getContent());
        assertEquals(2, multipart.getCount());
        assertEquals("中文内容", multipart.getBodyPart(0).getContent());
        assertEquals(delivery.htmlContent, multipart.getBodyPart(1).getContent());
        assertTrue(multipart.getBodyPart(1).getContentType().toLowerCase().contains("utf-8"));

        var raw = new ByteArrayOutputStream();
        message.writeTo(raw);
        String serialized = raw.toString(StandardCharsets.UTF_8);
        assertTrue(serialized.contains("multipart/alternative"));
        assertTrue(serialized.contains("text/plain"));
        assertTrue(serialized.contains("text/html"));
    }

    @Test
    void resolvesAngusSmtpTransportProvider() throws Exception {
        Transport transport = Session.getInstance(new java.util.Properties()).getTransport("smtp");
        assertInstanceOf(SMTPTransport.class, transport);
        transport.close();
    }

    @Test
    void resolvesAngusStreamProvider() {
        assertInstanceOf(MailStreamProvider.class, StreamProvider.provider());
    }

    @Test
    void resolvesAngusActivationProvidersAndMultipartHandlers() {
        assertTrue(ServiceLoader.load(MailcapRegistryProvider.class).findFirst().isPresent());
        assertTrue(ServiceLoader.load(MimeTypeRegistryProvider.class).findFirst().isPresent());
        CommandMap commandMap = CommandMap.getDefaultCommandMap();
        assertInstanceOf(MailcapCommandMap.class, commandMap);
        assertNotNull(commandMap.createDataContentHandler("multipart/alternative"));
        assertNotNull(commandMap.createDataContentHandler("text/plain"));
        assertNotNull(commandMap.createDataContentHandler("text/html"));
    }

    @Test
    void formatsDeliveryErrorWithRootCause() {
        MessagingException exception = new MessagingException(
                "IOException while sending message",
                new IOException("no object DCH for MIME type multipart/alternative"));
        String formatted = EmailDeliveryService.formatDeliveryError(exception);
        assertTrue(formatted.contains("IOException while sending message"));
        assertTrue(formatted.contains("no object DCH for MIME type multipart/alternative"));
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
