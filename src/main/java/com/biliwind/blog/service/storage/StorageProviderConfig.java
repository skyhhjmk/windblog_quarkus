package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class StorageProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageProviderConfig.class);

    private final JsonNode configJson;
    private final List<String> supportedTypes;
    private final String cdnDomain;
    private final boolean cdnEnabled;

    public StorageProviderConfig(JsonNode configJson, List<String> supportedTypes,
                                 String cdnDomain, boolean cdnEnabled) {
        this.configJson = configJson;
        this.supportedTypes = supportedTypes;
        this.cdnDomain = cdnDomain;
        this.cdnEnabled = cdnEnabled;
    }

    public static StorageProviderConfig fromEntityJson(String configJsonStr, String supportedTypesStr,
                                                       String cdnDomain, boolean cdnEnabled,
                                                       ObjectMapper objectMapper) {
        JsonNode configJson = null;
        List<String> supportedTypes = new ArrayList<>();

        if (configJsonStr != null) {
            try {
                configJson = objectMapper.readTree(configJsonStr);
            } catch (Exception e) {
                log.error("Failed to parse config_json: {}", configJsonStr, e);
            }
        }

        if (supportedTypesStr != null) {
            try {
                JsonNode typesNode = objectMapper.readTree(supportedTypesStr);
                if (typesNode.isArray()) {
                    for (int i = 0; i < typesNode.size(); i++) {
                        String typeEntry = typesNode.get(i).asText();
                        supportedTypes.add(typeEntry);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse supported_types: {}", supportedTypesStr, e);
                supportedTypes.add("*");
            }
        } else {
            supportedTypes.add("*");
        }

        return new StorageProviderConfig(configJson, supportedTypes, cdnDomain, cdnEnabled);
    }

    public JsonNode getConfigJson() {
        return configJson;
    }

    public List<String> getSupportedTypes() {
        return supportedTypes;
    }

    public String getCdnDomain() {
        return cdnDomain;
    }

    public boolean isCdnEnabled() {
        return cdnEnabled;
    }
}
