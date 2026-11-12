package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.service.ConfigManager;
import com.biliwind.blog.service.settings.RegionalSettings;
import com.biliwind.blog.service.settings.SettingCatalogue;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSystemSettingsControllerTest {

    @Test
    void shouldMaskSecretsInsideJsonArrays() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree("{\"providers\":[{\"apiKey\":\"secret\",\"name\":\"demo\"}]}");

        JsonNode masked = reader().masked(SettingKey.ELASTICSEARCH, input);

        assertEquals("***REDACTED***", masked.get("providers").get(0).get("apiKey").asText());
        assertEquals("demo", masked.get("providers").get(0).get("name").asText());
        assertTrue(masked.isObject());
    }

    @Test
    void shouldNotTreatSeoKeywordsAsASecret() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree("{\"keywords\":[\"seo\",\"blog\"],\"description\":\"demo\"}");

        JsonNode masked = reader().masked(SettingKey.SITE_INFO, input);

        assertEquals(input.get("keywords"), masked.get("keywords"));
        assertFalse(reader().containsSecret(SettingKey.SITE_INFO, input));
    }

    @Test
    void shouldDeriveVerificationFromTheCatalogue() {
        SystemSetting normalSetting = new SystemSetting();
        normalSetting.settingLevel = com.biliwind.blog.model.SettingLevel.NORMAL.name();
        assertFalse(normalSetting.requiresVerification(), "普通设置不需要验证期");

        SystemSetting criticalSetting = new SystemSetting();
        criticalSetting.settingLevel = com.biliwind.blog.model.SettingLevel.CRITICAL.name();
        assertTrue(criticalSetting.requiresVerification(), "关键设置必须进入验证期");

        assertTrue(SettingCatalogue.requiresVerification("elasticsearch"));
        assertTrue(SettingCatalogue.requiresVerification("security_network"));
        assertTrue(SettingCatalogue.requiresVerification("comment_security"));
        assertFalse(SettingCatalogue.requiresVerification("site_info"));
        assertTrue(SettingCatalogue.find(SettingKey.ELASTICSEARCH).secretFields().contains("password"));

        assertEquals(com.biliwind.blog.model.SettingLevel.NORMAL,
                com.biliwind.blog.model.SettingLevel.fromString("unknown"));
        assertEquals(com.biliwind.blog.model.SettingLevel.NORMAL,
                com.biliwind.blog.model.SettingLevel.fromString(null));
        assertEquals(com.biliwind.blog.model.SettingLevel.CRITICAL,
                com.biliwind.blog.model.SettingLevel.fromString("CRITICAL"));
    }

    @Test
    void shouldIncludeSettingLevelInSystemSettingView() {
        AdminSystemSettingsController controller = new AdminSystemSettingsController();
        controller.mapper = new ObjectMapper();
        controller.settings = reader();

        SystemSetting setting = new SystemSetting();
        setting.id = 100L;
        setting.configKey = "security_network";
        setting.configValue = controller.mapper.createObjectNode().put("trusted_proxy_cidrs", "10.0.0.0/8");
        setting.configType = "object";
        setting.groupName = "安全设置";
        setting.uiSchema = controller.mapper.createObjectNode();
        setting.description = "安全设置";
        setting.version = 2;
        setting.isFrozen = true;
        setting.settingLevel = com.biliwind.blog.model.SettingLevel.CRITICAL.name();

        com.biliwind.blog.controller.api.admin.dto.SystemSettingView view = controller.toView(setting);

        assertEquals("CRITICAL", view.settingLevel());
        assertTrue(view.requiresVerification());
        assertTrue(view.isFrozen());
    }

    private static SettingsReader reader() {
        ConfigManager config = new ConfigManager() {
            @Override
            public JsonNode get(String key) {
                return null;
            }
        };
        return new SettingsReader(config, new RegionalSettings(config));
    }
}
