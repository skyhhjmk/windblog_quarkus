package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 cache in front of {@link SystemConfigService}. Reads expose raw JSON only;
 * typed reads and canonical defaults belong to
 * {@link com.biliwind.blog.service.settings.SettingsReader}.
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
        // The initializer method is not intercepted by @Transactional, so the
        // transaction must be opened explicitly before loading rows.
        QuarkusTransaction.requiringNew().run(this::refreshAll);
    }

    @Transactional
    public void refreshAll() {
        List<SystemSetting> settings = SystemSetting.listAll();
        for (SystemSetting setting : settings) {
            localCache.put(setting.configKey, setting.configValue);
            systemConfigService.updateCache(setting.configKey, setting.configValue);
        }
        LOG.info("ConfigManager 本地与 Redis 缓存已刷新。总键数: " + localCache.size());
    }

    /** Reads only the local L1 cache; never touches Redis or the database. */
    public JsonNode getLocal(String key) {
        return localCache.get(key);
    }

    public JsonNode get(String key) {
        JsonNode local = localCache.get(key);
        if (local != null) {
            return local;
        }

        JsonNode val = systemConfigService.getConfig(key);
        if (val != null) {
            localCache.put(key, val);
        }
        return val;
    }

    /**
     * Cache refresh runs only after the changing transaction commits. A
     * rollback therefore leaves the previous committed value in L1 and Redis;
     * a rejected value can never be published to either cache.
     */
    public void onConfigChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) ConfigChangedEvent event) {
        LOG.infof("收到配置变更事件: %s, 更新本地及 Redis 缓存。", event.key);
        if (event.newValue != null) {
            localCache.put(event.key, event.newValue);
            systemConfigService.updateCache(event.key, event.newValue);
        } else {
            localCache.remove(event.key);
            systemConfigService.evictCache(event.key);
        }
    }

    /**
     * Applies a configuration change received from another application node.
     * Redis already holds the authoritative shared cache entry, so this must
     * only update this node's L1 cache and never publish another event.
     */
    public void applyRemoteConfigChange(String key, JsonNode newValue) {
        if (newValue != null && !newValue.isNull()) {
            localCache.put(key, newValue);
        } else {
            localCache.remove(key);
        }
        LOG.infof("已应用来自其他节点的配置变更: %s", key);
    }
}
