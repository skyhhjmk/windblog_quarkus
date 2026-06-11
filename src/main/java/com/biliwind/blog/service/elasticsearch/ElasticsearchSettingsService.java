package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.service.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ElasticsearchSettingsService {

    public static final String SETTING_KEY = "elasticsearch";

    @Inject
    ConfigManager configManager;

    public ElasticsearchSettings getSettings() {
        return parse(configManager.get(SETTING_KEY));
    }

    public ElasticsearchSettings parse(JsonNode settingValue) {
        String defaultHosts = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.hosts", String.class)
                .orElse("http://127.0.0.1:9200");
        String defaultUsername = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.username", String.class)
                .orElse("");
        String defaultPassword = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.password", String.class)
                .orElse("");
        int defaultTimeoutSeconds = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.health-check.timeout-seconds", Integer.class)
                .orElse(5);

        if (settingValue == null || !settingValue.isObject()) {
            return new ElasticsearchSettings(
                    true,
                    normalizeHosts(defaultHosts),
                    defaultUsername,
                    defaultPassword,
                    defaultTimeoutSeconds,
                    "ik_max_word",
                    List.of()
            );
        }

        boolean enabled = settingValue.path("enabled").asBoolean(true);
        String hosts = textValue(settingValue, "hosts", defaultHosts);
        String username = textValueAllowBlank(settingValue, "username", defaultUsername);
        String password = textValueAllowBlank(settingValue, "password", defaultPassword);
        int timeoutSeconds = settingValue.path("timeout_seconds").asInt(defaultTimeoutSeconds);
        if (timeoutSeconds < 1) {
            timeoutSeconds = 1;
        }
        String analyzer = normalizeAnalyzer(textValue(settingValue, "analyzer", "ik_max_word"));
        List<String> synonyms = parseSynonyms(settingValue.path("synonyms"));

        return new ElasticsearchSettings(
                enabled,
                normalizeHosts(hosts),
                username,
                password,
                timeoutSeconds,
                analyzer,
                synonyms
        );
    }

    private List<String> parseSynonyms(JsonNode synonymRules) {
        List<String> synonyms = new ArrayList<>();
        if (!synonymRules.isArray()) {
            return synonyms;
        }

        for (JsonNode rule : synonymRules) {
            String mode = rule.path("mode").asText("equivalent");
            List<String> terms = new ArrayList<>();
            JsonNode termNodes = rule.path("terms");
            if (termNodes.isArray()) {
                for (JsonNode termNode : termNodes) {
                    String term = termNode.asText("").trim();
                    if (!term.isBlank()) {
                        terms.add(term);
                    }
                }
            }
            if (terms.isEmpty()) {
                continue;
            }

            if ("mapping".equals(mode)) {
                String target = rule.path("target").asText("").trim();
                if (!target.isBlank()) {
                    synonyms.add(String.join(", ", terms) + " => " + target);
                }
            } else if (terms.size() > 1) {
                synonyms.add(String.join(", ", terms));
            }
        }
        return synonyms;
    }

    private String textValue(JsonNode root, String fieldName, String defaultValue) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText("").trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        return text;
    }

    private String textValueAllowBlank(JsonNode root, String fieldName, String defaultValue) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asText("").trim();
    }

    private String normalizeHosts(String hosts) {
        String normalizedHosts = hosts.trim();
        while (normalizedHosts.endsWith("/")) {
            normalizedHosts = normalizedHosts.substring(0, normalizedHosts.length() - 1);
        }
        return normalizedHosts;
    }

    private String normalizeAnalyzer(String analyzer) {
        if ("standard".equals(analyzer) || "ik_smart".equals(analyzer)) {
            return analyzer;
        }
        return "ik_max_word";
    }

    public record ElasticsearchSettings(
            boolean enabled,
            String hosts,
            String username,
            String password,
            int timeoutSeconds,
            String analyzer,
            List<String> synonyms
    ) {
    }
}
