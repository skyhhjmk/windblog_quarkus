package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ElasticsearchSettingsServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    // Keep this pure unit test independent of a developer/container ELASTICSEARCH_HOSTS override.
    private final ElasticsearchSettingsService settingsService = new ElasticsearchSettingsService(name -> java.util.Optional.empty());

    @Test
    void shouldParseConnectionAnalyzerAndSynonymSettings() {
        ObjectNode settingValue = objectMapper.createObjectNode();
        settingValue.put("enabled", false);
        settingValue.put("hosts", "http://elasticsearch:9200/");
        settingValue.put("username", "");
        settingValue.put("password", "");
        settingValue.put("timeout_seconds", 8);
        settingValue.put("analyzer", "ik_smart");

        ArrayNode synonymRules = settingValue.putArray("synonyms");
        synonymRules.addObject()
                .put("mode", "equivalent")
                .putArray("terms")
                .add("wp")
                .add("wordpress");
        ObjectNode mappingRule = synonymRules.addObject();
        mappingRule.put("mode", "mapping");
        mappingRule.putArray("terms")
                .add("博客系统")
                .add("blog system");
        mappingRule.put("target", "WindBlog");

        ElasticsearchSettingsService.ElasticsearchSettings settings = settingsService.parse(settingValue);

        assertFalse(settings.enabled());
        assertEquals("http://elasticsearch:9200", settings.hosts());
        assertEquals("", settings.username());
        assertEquals("", settings.password());
        assertEquals(8, settings.timeoutSeconds());
        assertEquals("ik_smart", settings.analyzer());
        assertEquals(
                java.util.List.of(
                        "wp, wordpress",
                        "博客系统, blog system => WindBlog"
                ),
                settings.synonyms()
        );
    }
}
