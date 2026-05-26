package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;

@ApplicationScoped
public class SystemConfigService {

    private static final Logger log = Logger.getLogger(SystemConfigService.class);
    private static final String CACHE_PREFIX = "windblog:config:";

    private final ValueCommands<String, String> valueCommands;
    private final ObjectMapper mapper;

    public SystemConfigService(RedisDataSource redisDataSource, ObjectMapper mapper) {
        this.mapper = mapper;
        this.valueCommands = redisDataSource.value(String.class);
    }

    /**
     * 获取配置项
     */
    public JsonNode getConfig(String key) {
        String cacheKey = CACHE_PREFIX + key;
        String cachedValue = null;
        try {
            cachedValue = valueCommands.get(cacheKey);
        } catch (Exception e) {
            log.warnf("读取 Redis 配置缓存失败，改用数据库配置: %s, 错误: %s", key, e.getMessage());
        }

        if (cachedValue != null) {
            try {
                return mapper.readTree(cachedValue);
            } catch (Exception e) {
                log.errorf("解析 Redis 缓存配置失败: %s, 错误: %s", key, e.getMessage());
            }
        }

        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting != null) {
            updateCache(key, setting.configValue);
            return setting.configValue;
        }

        return null;
    }

    public void updateCache(String key, JsonNode value) {
        try {
            String cacheKey = CACHE_PREFIX + key;
            String jsonString = mapper.writeValueAsString(value);
            valueCommands.set(cacheKey, jsonString, new SetArgs().ex(Duration.ofDays(7)));
            log.debugf("配置缓存已更新: %s", key);
        } catch (Exception e) {
            log.errorf("更新 Redis 缓存失败: %s, 错误: %s", key, e.getMessage());
        }
    }

    public String getString(String key, String defaultValue) {
        JsonNode node = getConfig(key);
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        return defaultValue;
    }

    public String getString(String key, String field, String defaultValue) {
        JsonNode node = getConfig(key);
        if (node != null && node.has(field)) {
            JsonNode fieldNode = node.get(field);
            if (fieldNode.isTextual()) {
                return fieldNode.asText();
            }
            return fieldNode.toString();
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, String field, boolean defaultValue) {
        JsonNode node = getConfig(key);
        if (node != null && node.has(field)) {
            return node.get(field).asBoolean();
        }
        return defaultValue;
    }

    public void evictCache(String key) {
        try {
            valueCommands.getdel(CACHE_PREFIX + key);
            log.infof("配置缓存已清除: %s", key);
        } catch (Exception e) {
            log.warnf("清除 Redis 配置缓存失败: %s, 错误: %s", key, e.getMessage());
        }
    }
}
