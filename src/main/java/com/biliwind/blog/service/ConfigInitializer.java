package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
            } else {
                boolean definitionChanged = false;
                if (!setting.uiSchema.equals(def.uiSchema) || !setting.groupName.equals(def.group) || !setting.description.equals(def.description)) {
                    setting.uiSchema = def.uiSchema;
                    setting.groupName = def.group;
                    setting.description = def.description;
                    definitionChanged = true;
                }

                boolean defaultValueChanged = false;
                if (setting.configValue != null && def.defaultValue != null && setting.configValue.isObject() && def.defaultValue.isObject()) {
                    defaultValueChanged = mergeMissingObjectFields((ObjectNode) setting.configValue, def.defaultValue);
                }

                if (definitionChanged || defaultValueChanged) {
                    setting.persist();
                    LOG.infof("Updated setting definition or default values: %s", def.key);
                }
            }
        }

        configManager.refreshAll();
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
        siteInfoFields.addObject().put("key", "description").put("label", "SEO描述").put("widget", "textarea");
        siteInfoFields.addObject().put("key", "author").put("label", "站点作者").put("widget", "input");
        siteInfoFields.addObject()
                .put("key", "site_url")
                .put("label", "本站链接")
                .put("widget", "input")
                .put("required", true)
                .put("hint", "用于友链反向链接检测，例如：https://example.com");

        ObjectNode siteInfoValue = mapper.createObjectNode();
        siteInfoValue.put("title", "WindBlog");
        siteInfoValue.put("subtitle", "极简主义者的技术博客");
        siteInfoValue.putArray("keywords").add("blog").add("tech").add("quarkus");
        siteInfoValue.put("description", "基于 Quarkus 和 Flutter 构建的极简博客系统，支持 AI 摘要与多端同步。");
        siteInfoValue.put("author", "BiliWind");
        siteInfoValue.put("site_url", "http://localhost:8080");

        list.add(new SettingDefinition("site_info", siteInfoValue, "object", "基础设置", siteInfoSchema, "网站基础信息设置"));

        // Footer Settings
        ObjectNode footerSchema = mapper.createObjectNode();
        footerSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode footerFields = footerSchema.putArray("fields");
        footerFields.addObject().put("key", "copyright").put("label", "版权信息").put("widget", "input");
        footerFields.addObject().put("key", "icp").put("label", "ICP备案号").put("widget", "input");
        footerFields.addObject().put("key", "public_security_record").put("label", "公安备案号").put("widget", "input");
        footerFields.addObject().put("key", "custom_html").put("label", "自定义页脚HTML").put("widget", "textarea");

        ObjectNode footerValue = mapper.createObjectNode();
        footerValue.put("copyright", "© 2026 WindBlog. All rights reserved.");
        footerValue.put("icp", "粤ICP备XXXXXXXX号");
        footerValue.put("public_security_record", "公网安备 XXXXXXXXXXXX号");
        footerValue.put("custom_html", "");

        list.add(new SettingDefinition("site_footer", footerValue, "object", "基础设置", footerSchema, "网站页脚设置"));

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

        list.add(new SettingDefinition("appearance", appearanceValue, "object", "外观设置", appearanceSchema, "外观样式设置"));

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

        list.add(new SettingDefinition("social_links", socialValue, "object", "外观设置", socialSchema, "社交链接设置"));

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

        list.add(new SettingDefinition("feature_toggles", featureValue, "object", "功能设置", featureSchema, "功能开关"));

        // AI Comment Audit
        ObjectNode aiAuditSchema = mapper.createObjectNode();
        aiAuditSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode aiAuditFields = aiAuditSchema.putArray("fields");
        aiAuditFields.addObject().put("key", "prompt").put("label", "AI 审核提示词").put("widget", "textarea").put("required", true);
        aiAuditFields.addObject().put("key", "allowAutoDecision").put("label", "允许 AI 自主决策").put("widget", "switch");

        ObjectNode aiAuditValue = mapper.createObjectNode();
        aiAuditValue.put("prompt", "你是一个评论审核专家。请审核以下评论内容，判断其是否包含不当内容（色情、暴力、政治敏感、广告垃圾等）。回答 JSON: {\"isSafe\": true/false, \"reason\": \"理由\", \"score\": 评分0-100}。待审核内容: {{content}}");
        aiAuditValue.put("allowAutoDecision", false);

        list.add(new SettingDefinition("ai_comment_audit", aiAuditValue, "object", "功能设置", aiAuditSchema, "AI 评论审核设置"));

        ObjectNode elasticsearchSchema = mapper.createObjectNode();
        elasticsearchSchema.put("type", "object");
        com.fasterxml.jackson.databind.node.ArrayNode elasticsearchFields = elasticsearchSchema.putArray("fields");
        elasticsearchFields.addObject()
                .put("key", "enabled")
                .put("label", "启用 Elasticsearch")
                .put("widget", "switch")
                .put("section", "基础设置");
        elasticsearchFields.addObject()
                .put("key", "hosts")
                .put("label", "连接地址")
                .put("widget", "input")
                .put("required", true)
                .put("section", "基础设置")
                .put("hint", "例如：http://elasticsearch:9200");
        elasticsearchFields.addObject()
                .put("key", "username")
                .put("label", "用户名")
                .put("widget", "input")
                .put("section", "基础设置");
        elasticsearchFields.addObject()
                .put("key", "password")
                .put("label", "密码")
                .put("widget", "password")
                .put("section", "基础设置");
        elasticsearchFields.addObject()
                .put("key", "timeout_seconds")
                .put("label", "连接超时（秒）")
                .put("widget", "number")
                .put("section", "基础设置");
        com.fasterxml.jackson.databind.node.ObjectNode analyzerField = elasticsearchFields.addObject();
        analyzerField.put("key", "analyzer");
        analyzerField.put("label", "分词器");
        analyzerField.put("widget", "select");
        analyzerField.put("section", "基础设置");
        com.fasterxml.jackson.databind.node.ArrayNode analyzerOptions = analyzerField.putArray("options");
        analyzerOptions.addObject().put("label", "IK 最大词粒度").put("value", "ik_max_word");
        analyzerOptions.addObject().put("label", "IK 智能分词").put("value", "ik_smart");
        analyzerOptions.addObject().put("label", "标准分词器").put("value", "standard");
        elasticsearchFields.addObject()
                .put("key", "synonyms")
                .put("label", "同义词规则")
                .put("widget", "synonym_cards")
                .put("section", "同义词");

        ObjectNode elasticsearchValue = mapper.createObjectNode();
        String elasticsearchHosts = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.hosts", String.class)
                .orElse("http://127.0.0.1:9200");
        String elasticsearchUsername = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.username", String.class)
                .orElse("");
        String elasticsearchPassword = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.password", String.class)
                .orElse("");
        int elasticsearchTimeoutSeconds = ConfigProvider.getConfig()
                .getOptionalValue("elasticsearch.health-check.timeout-seconds", Integer.class)
                .orElse(5);
        elasticsearchValue.put("enabled", true);
        elasticsearchValue.put("hosts", elasticsearchHosts);
        elasticsearchValue.put("username", elasticsearchUsername);
        elasticsearchValue.put("password", elasticsearchPassword);
        elasticsearchValue.put("timeout_seconds", elasticsearchTimeoutSeconds);
        elasticsearchValue.put("analyzer", "ik_max_word");
        elasticsearchValue.putArray("synonyms");

        list.add(new SettingDefinition(
                "elasticsearch",
                elasticsearchValue,
                "object",
                "Elasticsearch",
                elasticsearchSchema,
                "Elasticsearch 搜索、分词与同义词设置"
        ));

        return list;
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
