package com.biliwind.blog.controller.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldNotTreatSeoKeywordsAsASecret() throws Exception {
        AdminSystemSettingsController controller = new AdminSystemSettingsController();
        Field mapperField = AdminSystemSettingsController.class.getDeclaredField("mapper");
        mapperField.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();
        mapperField.set(controller, mapper);

        Method maskSecrets = AdminSystemSettingsController.class
                .getDeclaredMethod("maskSecrets", String.class, JsonNode.class);
        maskSecrets.setAccessible(true);
        Method containsSecret = AdminSystemSettingsController.class
                .getDeclaredMethod("containsSecret", String.class, JsonNode.class);
        containsSecret.setAccessible(true);

        JsonNode input = mapper.readTree("{\"keywords\":[\"seo\",\"blog\"],\"description\":\"demo\"}");
        JsonNode masked = (JsonNode) maskSecrets.invoke(controller, "site_info", input);

        assertEquals(input.get("keywords"), masked.get("keywords"));
        assertFalse((Boolean) containsSecret.invoke(controller, "site_info", input));
    }

    @Test
    void shouldCorrectlyIdentifySettingVerificationRequirement() {
        com.biliwind.blog.model.SystemSetting normalSetting = new com.biliwind.blog.model.SystemSetting();
        normalSetting.settingLevel = com.biliwind.blog.model.SettingLevel.NORMAL.name();
        assertFalse(normalSetting.requiresVerification(), "普通设置不需要验证期");

        com.biliwind.blog.model.SystemSetting criticalSetting = new com.biliwind.blog.model.SystemSetting();
        criticalSetting.settingLevel = com.biliwind.blog.model.SettingLevel.CRITICAL.name();
        assertTrue(criticalSetting.requiresVerification(), "关键设置必须进入验证期");

        // 大小写容错
        criticalSetting.settingLevel = "critical";
        assertTrue(criticalSetting.requiresVerification());

        // 枚举转换容错
        assertEquals(com.biliwind.blog.model.SettingLevel.NORMAL, com.biliwind.blog.model.SettingLevel.fromString("unknown"));
        assertEquals(com.biliwind.blog.model.SettingLevel.NORMAL, com.biliwind.blog.model.SettingLevel.fromString(null));
        assertEquals(com.biliwind.blog.model.SettingLevel.CRITICAL, com.biliwind.blog.model.SettingLevel.fromString("CRITICAL"));
    }

    @Test
    void shouldIncludeSettingLevelInSystemSettingView() throws Exception {
        AdminSystemSettingsController controller = new AdminSystemSettingsController();
        Field mapperField = AdminSystemSettingsController.class.getDeclaredField("mapper");
        mapperField.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();
        mapperField.set(controller, mapper);

        Method toView = AdminSystemSettingsController.class
                .getDeclaredMethod("toView", com.biliwind.blog.model.SystemSetting.class);
        toView.setAccessible(true);

        com.biliwind.blog.model.SystemSetting setting = new com.biliwind.blog.model.SystemSetting();
        setting.id = 100L;
        setting.configKey = "security_network";
        setting.configValue = mapper.createObjectNode().put("trusted_proxy_cidrs", "10.0.0.0/8");
        setting.configType = "object";
        setting.groupName = "安全设置";
        setting.uiSchema = mapper.createObjectNode();
        setting.description = "安全设置";
        setting.version = 2;
        setting.isFrozen = true;
        setting.settingLevel = com.biliwind.blog.model.SettingLevel.CRITICAL.name();

        com.biliwind.blog.controller.api.admin.dto.SystemSettingView view =
                (com.biliwind.blog.controller.api.admin.dto.SystemSettingView) toView.invoke(controller, setting);

        assertEquals("CRITICAL", view.settingLevel());
        assertTrue(view.requiresVerification());
        assertTrue(view.isFrozen());
    }
}
