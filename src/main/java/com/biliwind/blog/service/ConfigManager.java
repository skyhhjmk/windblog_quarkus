package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ConfigManager {

    private static final Logger LOG = Logger.getLogger(ConfigManager.class);

    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    @Inject
    protected void init() {
        LOG.info("Initializing ConfigManager cache...");
        refreshAll();
    }

    @Transactional
    public void refreshAll() {
        SystemSetting.listAll().forEach(item -> {
            SystemSetting setting = (SystemSetting) item;
            cache.put(setting.configKey, setting.configValue);
        });
        LOG.info("ConfigManager cache refreshed. Total keys: " + cache.size());
    }

    public JsonNode get(String key) {
        return cache.computeIfAbsent(key, k -> {
            SystemSetting setting = SystemSetting.findByKey(k);
            return setting != null ? setting.configValue : null;
        });
    }

    public String getString(String key, String defaultValue) {
        JsonNode node = get(key);
        return (node != null && node.isTextual()) ? node.asText() : defaultValue;
    }

    public Integer getInt(String key, Integer defaultValue) {
        JsonNode node = get(key);
        return (node != null && node.isInt()) ? node.asInt() : defaultValue;
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        JsonNode node = get(key);
        return (node != null && node.isBoolean()) ? node.asBoolean() : defaultValue;
    }

    public void onConfigChanged(@Observes ConfigChangedEvent event) {
        LOG.infof("Config changed for key: %s, updating cache.", event.key);
        if (event.newValue != null) {
            cache.put(event.key, event.newValue);
        } else {
            cache.remove(event.key);
        }
    }
}
