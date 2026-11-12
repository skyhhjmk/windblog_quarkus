package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.service.settings.SettingCatalogue;
import com.biliwind.blog.service.settings.SettingDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.Map;

/**
 * Applies the catalogue to the database. The catalogue is the only place that
 * declares a setting; this class never defines a default or a level itself.
 */
@ApplicationScoped
public class ConfigInitializer {

    private static final Logger LOG = Logger.getLogger(ConfigInitializer.class);

    @Inject
    ConfigManager configManager;

    void onStart(@Observes StartupEvent ignored) {
        LOG.info("Syncing system setting definitions...");
        SettingCatalogue.validate();
        QuarkusTransaction.requiringNew().run(this::syncDefinitions);
        configManager.refreshAll();
    }

    private void syncDefinitions() {
        for (SettingDefinition def : SettingCatalogue.definitions()) {
            SystemSetting setting = SystemSetting.findByKey(def.key().key());
            if (setting == null) {
                setting = new SystemSetting();
                setting.configKey = def.key().key();
                setting.configValue = def.defaultValue().deepCopy();
                setting.configType = def.configType();
                setting.groupName = def.group();
                setting.uiSchema = def.uiSchema().deepCopy();
                setting.description = def.description();
                setting.settingLevel = def.level().name();
                setting.persist();
                LOG.infof("Added missing setting: %s", def.key().key());
            } else {
                boolean definitionChanged = false;
                if (!setting.uiSchema.equals(def.uiSchema())
                        || !setting.groupName.equals(def.group())
                        || !setting.description.equals(def.description())
                        || !def.level().name().equalsIgnoreCase(setting.settingLevel)
                        || !def.configType().equals(setting.configType)) {
                    setting.uiSchema = def.uiSchema().deepCopy();
                    setting.groupName = def.group();
                    setting.description = def.description();
                    setting.settingLevel = def.level().name();
                    setting.configType = def.configType();
                    definitionChanged = true;
                }

                boolean defaultValueChanged = false;
                if (setting.configValue != null && def.defaultValue() != null
                        && setting.configValue.isObject() && def.defaultValue().isObject()) {
                    defaultValueChanged = mergeMissingObjectFields(
                            (ObjectNode) setting.configValue, def.defaultValue());
                    if ("appearance".equals(def.key().key())) {
                        if ("/favicon.ico".equals(setting.configValue.path("favicon_url").asText())) {
                            ((ObjectNode) setting.configValue).put("favicon_url", "");
                            defaultValueChanged = true;
                        }
                        if ("/logo.png".equals(setting.configValue.path("logo_url").asText())) {
                            ((ObjectNode) setting.configValue).put("logo_url", "");
                            defaultValueChanged = true;
                        }
                    }
                }

                if (definitionChanged || defaultValueChanged) {
                    setting.persist();
                    LOG.infof("Updated setting definition or default values: %s", def.key().key());
                }
            }
        }
    }

    private boolean mergeMissingObjectFields(ObjectNode currentValue, JsonNode defaultValue) {
        boolean changed = false;
        Iterator<Map.Entry<String, JsonNode>> defaultFields = defaultValue.fields();
        while (defaultFields.hasNext()) {
            Map.Entry<String, JsonNode> field = defaultFields.next();
            String fieldKey = field.getKey();
            if (!currentValue.has(fieldKey)) {
                currentValue.set(fieldKey, field.getValue().deepCopy());
                changed = true;
            }
        }
        return changed;
    }
}
