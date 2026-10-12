package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EmailChannel;
import com.biliwind.blog.service.storage.StorageConfigProtector;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminEmailChannelControllerTest {
    private static class TestProtector extends StorageConfigProtector {
        int calls;
        boolean fail;
        @Override
        public String protectForStorage(String json) {
            calls++;
            if (fail) throw new IllegalStateException();
            assertTrue(json.contains("smtp-password"));
            return "encrypted";
        }
    }

    private AdminEmailChannelController controller() {
        var controller = new AdminEmailChannelController();
        controller.objectMapper = new ObjectMapper();
        controller.storageConfigProtector = new TestProtector();
        return controller;
    }

    private AdminEmailChannelController.EmailChannelRequest request() {
        var request = new AdminEmailChannelController.EmailChannelRequest();
        request.name = " SMTP ";
        request.host = " smtp.example.com ";
        request.port = 2525;
        request.securityMode = "STARTTLS";
        request.fromAddress = " sender@example.com ";
        request.enabled = true;
        return request;
    }

    @Test
    void normalizesConfigurationAndDefaultsOptionalFields() {
        var channel = new EmailChannel();
        controller().applyRequest(channel, request());
        assertEquals("SMTP", channel.name);
        assertEquals("smtp.example.com", channel.host);
        assertEquals(2525, channel.port);
        assertEquals("CUSTOM_SMTP", channel.provider);
        assertEquals("WindBlog", channel.fromName);
        assertEquals("sender@example.com", channel.fromAddress);
    }

    @Test
    void preservesPasswordForBlankAndMaskedUpdates() {
        var controller = controller();
        var channel = new EmailChannel();
        channel.passwordEncrypted = "existing";
        for (String value : new String[]{"", "******"}) {
            var request = request();
            request.password = value;
            controller.applyRequest(channel, request);
            assertEquals("existing", channel.passwordEncrypted);
        }
        assertEquals(0, ((TestProtector) controller.storageConfigProtector).calls);
    }

    @Test
    void encryptsNewPasswordAndReportsMissingKey() {
        var controller = controller();
        var request = request();
        request.password = "smtp-password";

        var channel = new EmailChannel();
        controller.applyRequest(channel, request);
        assertEquals("encrypted", channel.passwordEncrypted);
        ((TestProtector) controller.storageConfigProtector).fail = true;
        assertEquals(503, assertThrows(WebApplicationException.class,
                () -> controller.applyRequest(channel, request)).getResponse().getStatus());
    }

    @Test
    void rejectsInvalidPortsAndAddresses() {
        var controller = controller();
        for (int port : new int[]{0, -1, 65536}) {
            var request = request();
            request.port = port;
            assertEquals(400, assertThrows(WebApplicationException.class,
                    () -> controller.applyRequest(new EmailChannel(), request)).getResponse().getStatus());
        }
        for (String address : new String[]{"", "bad", "a@example.com,b@example.com", "Name <a@example.com>"}) {
            assertThrows(WebApplicationException.class,
                    () -> AdminEmailChannelController.validateAddress(address, "邮箱"));
        }
    }
}
