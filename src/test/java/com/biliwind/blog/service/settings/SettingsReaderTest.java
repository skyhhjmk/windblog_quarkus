package com.biliwind.blog.service.settings;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missingValuesUseTheSingleDeclaredDefault() {
        SettingsReader settings = reader(new HashMap<>());

        assertTrue(settings.flag(SettingKey.FEATURE_TOGGLES, "enable_comment"));
        assertTrue(settings.flag(SettingKey.AMP, "enabled"));
        assertEquals("#1A1A1A", settings.text(SettingKey.APPEARANCE, "theme_color"));
        assertEquals(16, settings.number(SettingKey.COMMENT_SECURITY, "pow_difficulty"));
        assertEquals(50, settings.number(SettingKey.GAMIFICATION, "level_up_reward"));
    }

    @Test
    void wrongTypesFallBackToTheDeclaredDefaultInsteadOfCoercing() {
        Map<String, JsonNode> values = new HashMap<>();
        values.put("feature_toggles", MAPPER.createObjectNode().put("enable_comment", "not-a-boolean"));
        values.put("comment_security", MAPPER.createObjectNode().put("pow_difficulty", "not-a-number"));
        SettingsReader settings = reader(values);

        assertTrue(settings.flag(SettingKey.FEATURE_TOGGLES, "enable_comment"));
        assertEquals(16, settings.number(SettingKey.COMMENT_SECURITY, "pow_difficulty"));
    }

    @Test
    void storedObjectsGainMissingCatalogueDefaults() {
        Map<String, JsonNode> values = new HashMap<>();
        values.put("seo", MAPPER.createObjectNode().put("index_enabled", false));
        SettingsReader settings = reader(values);

        assertTrue(settings.flag(SettingKey.SEO, "follow_enabled"));
        assertFalse(settings.flag(SettingKey.SEO, "index_enabled"));
        assertTrue(settings.object(SettingKey.SEO).has("internal_link_graph_enabled"));
    }

    @Test
    void maskingUsesTheCatalogueSecretFields() {
        SettingsReader settings = reader(new HashMap<>());
        JsonNode elasticsearch = MAPPER.createObjectNode()
                .put("hosts", "http://es:9200")
                .put("username", "elastic")
                .put("password", "super-secret");

        JsonNode masked = settings.masked(SettingKey.ELASTICSEARCH, elasticsearch);

        assertEquals("http://es:9200", masked.path("hosts").asText());
        assertEquals("elastic", masked.path("username").asText());
        assertEquals("***REDACTED***", masked.path("password").asText());
        assertTrue(settings.containsSecret(SettingKey.ELASTICSEARCH, elasticsearch));
    }

    @Test
    void redactedSecretSubmissionKeepsTheStoredValue() {
        SettingsReader settings = reader(new HashMap<>());
        ObjectNode current = MAPPER.createObjectNode().put("password", "old").put("username", "u");
        ObjectNode submitted = MAPPER.createObjectNode().put("password", "***REDACTED***").put("username", "v");

        JsonNode merged = settings.mergeSecretValues(SettingKey.ELASTICSEARCH, current, submitted);

        assertEquals("old", merged.path("password").asText());
        assertEquals("v", merged.path("username").asText());
    }

    @Test
    void regionalResolutionHidesTheFourStorageShapes() {
        Map<String, JsonNode> values = new HashMap<>();
        ObjectNode appearance = MAPPER.createObjectNode().put("favicon_url", "/global.ico");
        appearance.putObject("favicon_url_regions").put("cn", "/cn.ico");
        appearance.putObject("regions").putObject("us").put("favicon_url", "/us.ico");
        values.put("appearance", appearance);

        ObjectNode siteInfo = MAPPER.createObjectNode().put("title", "Global")
                .put("site_url", "https://global.example");
        siteInfo.putObject("site_url_regions").put("cn", "https://cn.example");
        siteInfo.putObject("regions").putObject("cn").put("title", "China");
        values.put("site_info", siteInfo);

        ObjectNode language = MAPPER.createObjectNode().put("default_language", "zh-cn");
        language.putObject("regions").put("global", "en-US").put("cn", "zh-cn");
        values.put("site_language", language);

        SettingsReader settings = reader(values);

        assertEquals("/cn.ico", settings.favicon(BlogRegion.CN));
        assertEquals("/us.ico", settings.favicon(BlogRegion.US));
        assertEquals("/global.ico", settings.favicon(BlogRegion.GLOBAL));
        assertEquals("https://cn.example", settings.siteUrl(BlogRegion.CN));
        assertEquals("https://global.example", settings.siteUrl(BlogRegion.US));
        assertEquals("China", settings.regionalText(SettingKey.SITE_INFO, "title", BlogRegion.CN));
        assertEquals("en-US", settings.language(BlogRegion.US));
        assertEquals("zh-cn", settings.language(BlogRegion.CN));
    }

    private static SettingsReader reader(Map<String, JsonNode> values) {
        ConfigManager config = new ConfigManager() {
            @Override
            public JsonNode get(String key) {
                return values.get(key);
            }
        };
        return new SettingsReader(config, new RegionalSettings(config));
    }
}
