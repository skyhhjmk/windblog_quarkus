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
        siteInfoFields.addObject().put("key", "subtitle").put("label", "站点副标题").put("widget", "input");
        siteInfoFields.addObject().put("key", "keywords").put("label", "SEO关键词").put("widget", "tag_input");
        siteInfoFields.addObject().put("key", "description").put("label", "SEO描述").put("widget", "input");

        ObjectNode siteInfoValue = mapper.createObjectNode();
        siteInfoValue.put("title", "WindBlog");
        siteInfoValue.put("subtitle", "极简主义者的技术博客");
        siteInfoValue.putArray("keywords").add("blog").add("tech");
        siteInfoValue.put("description", "基于 Quarkus 和 Flutter 构建的极简博客系统");

        list.add(new SettingDefinition("site_info", siteInfoValue, "object", "basic", siteInfoSchema, "网站基础信息设置"));

        // Footer Settings
        ObjectNode footerSchema = mapper.createObjectNode();
        footerSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode footerFields = footerSchema.putArray("fields");
        footerFields.addObject().put("key", "copyright").put("label", "版权信息").put("widget", "input");
        footerFields.addObject().put("key", "icp").put("label", "备案信息").put("widget", "input");
        footerFields.addObject().put("key", "custom_html").put("label", "自定义页脚HTML").put("widget", "input");

        ObjectNode footerValue = mapper.createObjectNode();
        footerValue.put("copyright", "© 2026 WindBlog. All rights reserved.");
        footerValue.put("icp", "粤ICP备XXXXXXXX号");
        footerValue.put("custom_html", "");

        list.add(new SettingDefinition("site_footer", footerValue, "object", "basic", footerSchema, "网站页脚设置"));

        // Appearance
        ObjectNode appearanceSchema = mapper.createObjectNode();
        appearanceSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode appearanceFields = appearanceSchema.putArray("fields");
        appearanceFields.addObject().put("key", "logo_url").put("label", "Logo链接").put("widget", "input");
        appearanceFields.addObject().put("key", "favicon_url").put("label", "Favicon链接").put("widget", "input");
        appearanceFields.addObject().put("key", "theme_color").put("label", "主题色").put("widget", "input");

        ObjectNode appearanceValue = mapper.createObjectNode();
        appearanceValue.put("logo_url", "/logo.png");
        appearanceValue.put("favicon_url", "/favicon.ico");
        appearanceValue.put("theme_color", "#1A1A1A");

        list.add(new SettingDefinition("appearance", appearanceValue, "object", "ui", appearanceSchema, "外观样式设置"));

        // Social Links
        ObjectNode socialSchema = mapper.createObjectNode();
        socialSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode socialFields = socialSchema.putArray("fields");
        socialFields.addObject().put("key", "github").put("label", "GitHub").put("widget", "input");
        socialFields.addObject().put("key", "twitter").put("label", "Twitter (X)").put("widget", "input");
        socialFields.addObject().put("key", "email").put("label", "联系邮箱").put("widget", "input");

        ObjectNode socialValue = mapper.createObjectNode();
        socialValue.put("github", "https://github.com");
        socialValue.put("twitter", "");
        socialValue.put("email", "admin@windblog.local");

        list.add(new SettingDefinition("social_links", socialValue, "object", "ui", socialSchema, "社交链接设置"));

        // Feature Toggle
        ObjectNode featureSchema = mapper.createObjectNode();
        featureSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode featureFields = featureSchema.putArray("fields");
        featureFields.addObject().put("key", "enable_comment").put("label", "开启评论").put("widget", "switch");
        featureFields.addObject().put("key", "enable_registration").put("label", "开启注册").put("widget", "switch");
        featureFields.addObject().put("key", "enable_ai_summary").put("label", "开启AI摘要").put("widget", "switch");

        ObjectNode featureValue = mapper.createObjectNode();
        featureValue.put("enable_comment", true);
        featureValue.put("enable_registration", true);
        featureValue.put("enable_ai_summary", true);

        list.add(new SettingDefinition("feature_toggles", featureValue, "object", "system", featureSchema, "功能开关"));

        // AI Comment Audit
        ObjectNode aiAuditSchema = mapper.createObjectNode();
        aiAuditSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode aiAuditFields = aiAuditSchema.putArray("fields");
        aiAuditFields.addObject().put("key", "prompt").put("label", "AI 审核提示词").put("widget", "textarea").put("required", true);
        aiAuditFields.addObject().put("key", "allowAutoDecision").put("label", "允许 AI 自主决策").put("widget", "switch");

        ObjectNode aiAuditValue = mapper.createObjectNode();
        aiAuditValue.put("prompt", "你是一个评论审核专家。请审核以下评论内容，判断其是否包含不当内容（色情、暴力、政治敏感、广告垃圾等）。回答 JSON: {\"isSafe\": true/false, \"reason\": \"理由\", \"score\": 评分0-100}。待审核内容: {{content}}");
        aiAuditValue.put("allowAutoDecision", false);

        list.add(new SettingDefinition("ai_comment_audit", aiAuditValue, "object", "system", aiAuditSchema, "AI 评论审核设置"));

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
