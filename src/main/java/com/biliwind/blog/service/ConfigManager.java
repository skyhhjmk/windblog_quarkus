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

/**
 * 配置管理器（旧组件重构版）
 * <p>
 * 现在作为 SystemConfigService 的本地一级缓存包装类。
 */
@ApplicationScoped
public class ConfigManager {

    private static final Logger LOG = Logger.getLogger(ConfigManager.class);

    private final Map<String, JsonNode> localCache = new ConcurrentHashMap<>();

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    protected void init() {
        LOG.info("初始化 ConfigManager (一级本地缓存)...");
        refreshAll();
    }

    @Transactional
    public void refreshAll() {
        SystemSetting.listAll().forEach(item -> {
            SystemSetting setting = (SystemSetting) item;
            localCache.put(setting.configKey, setting.configValue);
            // 同时更新 Redis 二级缓存
            systemConfigService.updateCache(setting.configKey, setting.configValue);
        });
        LOG.info("ConfigManager 本地与 Redis 缓存已刷新。总键数: " + localCache.size());
    }

    public JsonNode get(String key) {
        // 1. 尝试本地缓存
        JsonNode local = localCache.get(key);
        if (local != null) {
            return local;
        }

        // 2. 尝试 Redis/DB
        JsonNode val = systemConfigService.getConfig(key);
        if (val != null) {
            localCache.put(key, val);
        }
        return val;
    }

    public String getString(String key, String defaultValue) {
        return systemConfigService.getString(key, defaultValue);
    }

    public String getString(String key, String field, String defaultValue) {
        return systemConfigService.getString(key, field, defaultValue);
    }

    public Integer getInt(String key, Integer defaultValue) {
        JsonNode node = get(key);
        return (node != null && node.canConvertToInt()) ? node.asInt() : defaultValue;
    }

    public Integer getInt(String key, String field, Integer defaultValue) {
        JsonNode node = get(key);
        if (node != null && node.has(field)) {
            JsonNode fieldNode = node.get(field);
            return fieldNode.canConvertToInt() ? fieldNode.asInt() : defaultValue;
        }
        return defaultValue;
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        JsonNode node = get(key);
        return (node != null && node.isBoolean()) ? node.asBoolean() : defaultValue;
    }

    public Boolean getBoolean(String key, String field, Boolean defaultValue) {
        return systemConfigService.getBoolean(key, field, defaultValue);
    }

    /**
     * 响应配置变更事件
     */
    public void onConfigChanged(@Observes ConfigChangedEvent event) {
        LOG.infof("收到配置变更事件: %s, 更新本地及 Redis 缓存。", event.key);
        if (event.newValue != null) {
            localCache.put(event.key, event.newValue);
            systemConfigService.updateCache(event.key, event.newValue);
        } else {
            localCache.remove(event.key);
            systemConfigService.evictCache(event.key);
        }
    }
}
