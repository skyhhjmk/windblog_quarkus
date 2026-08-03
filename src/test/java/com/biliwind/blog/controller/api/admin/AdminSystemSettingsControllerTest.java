package com.biliwind.blog.controller.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSystemSettingsControllerTest {

    @Test
    void shouldMaskSecretsInsideJsonArrays() throws Exception {
        AdminSystemSettingsController controller = new AdminSystemSettingsController();
        Field mapperField = AdminSystemSettingsController.class.getDeclaredField("mapper");
        mapperField.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();
        mapperField.set(controller, mapper);

        Method maskSecrets = AdminSystemSettingsController.class
                .getDeclaredMethod("maskSecrets", String.class, JsonNode.class);
        maskSecrets.setAccessible(true);

        JsonNode input = mapper.readTree("{\"providers\":[{\"apiKey\":\"secret\",\"name\":\"demo\"}]}");
        JsonNode masked = (JsonNode) maskSecrets.invoke(controller, "ai.providers", input);

        assertEquals("***REDACTED***", masked.get("providers").get(0).get("apiKey").asText());
        assertEquals("demo", masked.get("providers").get(0).get("name").asText());
        assertTrue(masked.isObject());
    }
}
