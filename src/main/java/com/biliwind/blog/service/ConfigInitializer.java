package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ConfigInitializer {

    private static final Logger LOG = Logger.getLogger(ConfigInitializer.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    ConfigManager configManager;

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        LOG.info("Syncing system setting definitions...");
        List<SettingDefinition> definitions = getDefinitions();

        boolean changed = false;
        for (SettingDefinition def : definitions) {
            SystemSetting setting = SystemSetting.findByKey(def.key);
            if (setting == null) {
                setting = new SystemSetting();
                setting.configKey = def.key;
                setting.configValue = def.defaultValue;
                setting.configType = def.type;
                setting.groupName = def.group;
                setting.uiSchema = def.uiSchema;
                setting.description = def.description;
                setting.persist();
                LOG.infof("Added missing setting: %s", def.key);
                changed = true;
            } else {
                // Check if uiSchema or other metadata needs update
                if (!setting.uiSchema.equals(def.uiSchema) || !setting.groupName.equals(def.group)) {
                    setting.uiSchema = def.uiSchema;
                    setting.groupName = def.group;
                    setting.description = def.description;
                    setting.persist();
                    LOG.infof("Updated metadata for setting: %s", def.key);
                    changed = true;
                }
            }
        }

        if (changed) {
            configManager.refreshAll();
        }
    }

    private List<SettingDefinition> getDefinitions() {
        List<SettingDefinition> list = new ArrayList<>();

        // Site Info
        ObjectNode siteInfoSchema = mapper.createObjectNode();
        siteInfoSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode siteInfoFields = siteInfoSchema.putArray("fields");
        siteInfoFields.addObject().put("key", "title").put("label", "站点标题").put("widget", "input").put("required", true);
        siteInfoFields.addObject().put("key", "keywords").put("label", "SEO关键词").put("widget", "tag_input");

        ObjectNode siteInfoValue = mapper.createObjectNode();
        siteInfoValue.put("title", "WindBlog");
        siteInfoValue.putArray("keywords").add("blog").add("tech");

        list.add(new SettingDefinition("site_info", siteInfoValue, "object", "basic", siteInfoSchema, "网站基础信息设置"));

        // Feature Toggle
        ObjectNode featureSchema = mapper.createObjectNode();
        featureSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode featureFields = featureSchema.putArray("fields");
        featureFields.addObject().put("key", "enable_comment").put("label", "开启评论").put("widget", "switch");
        featureFields.addObject().put("key", "enable_registration").put("label", "开启注册").put("widget", "switch");

        ObjectNode featureValue = mapper.createObjectNode();
        featureValue.put("enable_comment", true);
        featureValue.put("enable_registration", true);

        list.add(new SettingDefinition("feature_toggles", featureValue, "object", "system", featureSchema, "功能开关"));

        return list;
    }

    private static class SettingDefinition {
        String key;
        com.fasterxml.jackson.databind.JsonNode defaultValue;
        String type;
        String group;
        com.fasterxml.jackson.databind.JsonNode uiSchema;
        String description;

        SettingDefinition(String key, com.fasterxml.jackson.databind.JsonNode defaultValue, String type, String group, com.fasterxml.jackson.databind.JsonNode uiSchema, String description) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.type = type;
            this.group = group;
            this.uiSchema = uiSchema;
            this.description = description;
        }
    }
}
