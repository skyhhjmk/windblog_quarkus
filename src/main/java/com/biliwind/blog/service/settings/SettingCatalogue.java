package com.biliwind.blog.service.settings;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.SettingLevel;
import com.biliwind.blog.service.SidebarTemplateData;
import com.biliwind.blog.service.ai.CommentModerationPrompt;
import com.biliwind.blog.service.ai.TranslationPrompt;
import com.biliwind.blog.service.ai.UserProfileModerationPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for every database-backed setting: its default,
 * schema, level and secret-ness. The old Liquibase seed INSERTs remain as
 * historical migrations, but the initializer now upserts this catalogue, so
 * migration-only keys and initializer-only keys no longer drift apart.
 */
public final class SettingCatalogue {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<SettingKey, SettingDefinition> DEFINITIONS = buildDefinitions();
    private static final Map<String, SettingDefinition> BY_KEY = indexByKey(DEFINITIONS);

    private SettingCatalogue() {
    }

    public static List<SettingDefinition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static SettingDefinition find(SettingKey key) {
        SettingDefinition definition = key == null ? null : DEFINITIONS.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown setting key: " + key);
        }
        return definition;
    }

    public static SettingDefinition findByKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    public static boolean requiresVerification(String key) {
        SettingDefinition definition = findByKey(key);
        return definition != null && definition.requiresVerification();
    }

    public static void validate() {
        for (SettingDefinition definition : DEFINITIONS.values()) {
            JsonNode value = definition.defaultValue();
            if (value == null) {
                throw new IllegalStateException("Setting " + definition.key() + " has no default value");
            }
            if ("object".equals(definition.configType()) && !value.isObject()) {
                throw new IllegalStateException("Setting " + definition.key() + " default is not an object");
            }
            if (!"object".equals(definition.configType())) {
                continue;
            }
            JsonNode fields = definition.uiSchema().path("fields");
            if (!fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                String fieldKey = field.path("key").asText("");
                if (fieldKey.isBlank() || fieldKey.contains(".")) {
                    continue;
                }
                if (!value.has(fieldKey)) {
                    throw new IllegalStateException("Setting " + definition.key()
                            + " schema field " + fieldKey + " has no default");
                }
            }
        }
    }

    private static Map<String, SettingDefinition> indexByKey(Map<SettingKey, SettingDefinition> definitions) {
        Map<String, SettingDefinition> byKey = new LinkedHashMap<>();
        for (SettingDefinition definition : definitions.values()) {
            if (byKey.put(definition.key().key(), definition) != null) {
                throw new IllegalStateException("Duplicate setting key: " + definition.key());
            }
        }
        return Map.copyOf(byKey);
    }

    private static Map<SettingKey, SettingDefinition> buildDefinitions() {
        Map<SettingKey, SettingDefinition> definitions = new LinkedHashMap<>();
        add(definitions, siteInfo());
        add(definitions, siteFooter());
        add(definitions, siteLanguage());
        add(definitions, appearance());
        add(definitions, socialLinks());
        add(definitions, sidebarLayout());
        add(definitions, featureToggles());
        add(definitions, amp());
        add(definitions, analyticsTracking());
        add(definitions, aiCommentAudit());
        add(definitions, aiUserProfileAudit());
        add(definitions, aiTranslation());
        add(definitions, aiOperationConfigs());
        add(definitions, elasticsearch());
        add(definitions, securityNetwork());
        add(definitions, commentSecurity());
        add(definitions, seo());
        add(definitions, legalTerms());
        add(definitions, legalPrivacy());
        add(definitions, gamification());
        add(definitions, dailyCheckInRewards());
        add(definitions, googleAdsense());
        return Map.copyOf(definitions);
    }

    private static void add(Map<SettingKey, SettingDefinition> definitions, SettingDefinition definition) {
        definitions.put(definition.key(), definition);
    }

    private static SettingDefinition siteInfo() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "title").put("label", "站点标题").put("widget", "input").put("required", true);
        fields.addObject().put("key", "subtitle").put("label", "站点副标题").put("widget", "input");
        fields.addObject().put("key", "keywords").put("label", "SEO关键词").put("widget", "tag_input");
        fields.addObject().put("key", "description").put("label", "SEO描述").put("widget", "textarea");
        fields.addObject().put("key", "author").put("label", "站点作者").put("widget", "input");
        fields.addObject().put("key", "author_url").put("label", "作者公开介绍页").put("widget", "input")
                .put("hint", "填写真实可公开访问的作者主页；仅用于作者姓名与站点作者一致的文章。");
        fields.addObject().put("key", "author_same_as").put("label", "作者身份链接").put("widget", "tag_input")
                .put("hint", "同一作者的公开社交账号或个人主页 URL，每个链接添加为一个标签。");
        fields.addObject()
                .put("key", "site_url")
                .put("label", "本站链接")
                .put("widget", "input")
                .put("required", true)
                .put("hint", "默认链接；用于友链反向链接检测，例如：https://example.com。区域未单独设置时使用此链接。");
        for (BlogRegion region : BlogRegion.values()) {
            if (region == BlogRegion.GLOBAL) {
                continue;
            }
            fields.addObject()
                    .put("key", "site_url_regions." + region.getCode())
                    .put("label", region.getDisplayName() + "本站链接")
                    .put("widget", "input")
                    .put("hint", "可选；留空时使用默认链接");
        }

        ObjectNode value = MAPPER.createObjectNode();
        value.put("title", "WindBlog");
        value.put("subtitle", "极简主义者的技术博客");
        value.putArray("keywords").add("blog").add("tech").add("quarkus");
        value.put("description", "基于 Quarkus 和 Flutter 构建的极简博客系统，支持 AI 摘要与多端同步。");
        value.put("author", "BiliWind");
        value.put("author_url", "");
        value.putArray("author_same_as");
        value.put("site_url", "http://localhost:8080");
        value.putObject("site_url_regions");
        SettingDefinition definition = definition(SettingKey.SITE_INFO, value, "基础设置", schema, "网站基础信息设置");
        return withRegion(definition, RegionalKind.OBJECT_REGIONS);
    }

    private static SettingDefinition siteFooter() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "copyright").put("label", "版权信息").put("widget", "input");
        fields.addObject().put("key", "icp").put("label", "ICP备案号").put("widget", "input");
        fields.addObject()
                .put("key", "icp_url")
                .put("label", "ICP备案号链接地址")
                .put("widget", "input")
                .put("hint", "可选，填写备案号点击后打开的完整网址");
        fields.addObject().put("key", "public_security_record").put("label", "公安备案号").put("widget", "input");
        fields.addObject()
                .put("key", "public_security_record_url")
                .put("label", "公安备案号链接地址")
                .put("widget", "input")
                .put("hint", "可选，填写备案号点击后打开的完整网址");
        ObjectNode recordDisplayRegionField = fields.addObject();
        recordDisplayRegionField.put("key", "record_display_region");
        recordDisplayRegionField.put("label", "备案号显示区域");
        recordDisplayRegionField.put("widget", "select");
        recordDisplayRegionField.put("hint", "仅在识别为所选区域的请求中显示备案号");
        ArrayNode recordDisplayRegionOptions = recordDisplayRegionField.putArray("options");
        recordDisplayRegionOptions.addObject().put("label", "全部区域").put("value", "all");
        for (BlogRegion region : BlogRegion.values()) {
            recordDisplayRegionOptions.addObject()
                    .put("label", region.getDisplayName())
                    .put("value", region.getCode());
        }
        fields.addObject().put("key", "custom_html").put("label", "自定义页脚HTML").put("widget", "textarea");

        ObjectNode value = MAPPER.createObjectNode();
        value.put("copyright", "© 2026 WindBlog. All rights reserved.");
        value.put("icp", "粤ICP备XXXXXXXX号");
        value.put("icp_url", "");
        value.put("public_security_record", "公网安备 XXXXXXXXXXXX号");
        value.put("public_security_record_url", "");
        value.put("record_display_region", "all");
        value.put("custom_html", "");
        SettingDefinition definition = definition(SettingKey.SITE_FOOTER, value, "基础设置", schema, "网站页脚设置");
        return withRegion(definition, RegionalKind.OBJECT_REGIONS);
    }

    private static SettingDefinition siteLanguage() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        ObjectNode languageField = fields.addObject();
        languageField.put("key", "default_language").put("label", "默认语言").put("widget", "select");
        ArrayNode languageOptions = languageField.putArray("options");
        languageOptions.addObject().put("label", "简体中文").put("value", "zh-cn");
        languageOptions.addObject().put("label", "English").put("value", "en-US");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("default_language", "zh-cn");
        ObjectNode regions = value.putObject("regions");
        regions.put("global", "en-US");
        regions.put("cn", "zh-cn");
        regions.put("hk", "zh-cn");
        regions.put("tw", "zh-cn");
        return withRegion(definition(SettingKey.SITE_LANGUAGE, value, "基础设置", schema,
                "多区域默认语言（global 为国际站默认，未单独配置的区域继承 global）"), RegionalKind.LANGUAGE);
    }

    private static SettingDefinition appearance() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject()
                .put("key", "logo_url")
                .put("label", "站点Logo（SEO结构化数据与社交分享）")
                .put("widget", "media_picker")
                .put("hint", "用于搜索引擎结构化数据（JSON-LD Publisher Logo）及社交媒体分享默认主图（OpenGraph og:image）。支持输入链接或从媒体库选择。");
        fields.addObject()
                .put("key", "favicon_url")
                .put("label", "Favicon（默认）")
                .put("widget", "media_picker")
                .put("hint", "网站默认图标；从媒体库选择图片。页面渲染时会根据访客所在区域自动适配切换 CDN 与域名；也可在下方按区域单独指定。");
        for (BlogRegion region : BlogRegion.values()) {
            if (region == BlogRegion.GLOBAL) {
                continue;
            }
            fields.addObject()
                    .put("key", "favicon_url_regions." + region.getCode())
                    .put("label", region.getDisplayName() + " Favicon")
                    .put("widget", "media_picker")
                    .put("hint", "可选；留空时自动继承默认 Favicon 并按当前区域自动切换 CDN/域名。");
        }
        fields.addObject().put("key", "theme_color").put("label", "主题色").put("widget", "input");

        ObjectNode value = MAPPER.createObjectNode();
        value.put("logo_url", "");
        value.put("favicon_url", "");
        value.putObject("favicon_url_regions");
        value.put("theme_color", "#1A1A1A");
        return withRegion(definition(SettingKey.APPEARANCE, value, "外观设置", schema, "外观样式设置"),
                RegionalKind.FAVICON);
    }

    private static SettingDefinition socialLinks() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "github").put("label", "GitHub").put("widget", "input");
        fields.addObject().put("key", "twitter").put("label", "Twitter (X)").put("widget", "input");
        fields.addObject().put("key", "email").put("label", "联系邮箱").put("widget", "input");

        ObjectNode value = MAPPER.createObjectNode();
        value.put("github", "https://github.com");
        value.put("twitter", "");
        value.put("email", "admin@windblog.local");
        return definition(SettingKey.SOCIAL_LINKS, value, "外观设置", schema, "社交链接设置");
    }

    private static SettingDefinition sidebarLayout() {
        ObjectNode schema = objectSchema();
        schema.putArray("fields").addObject()
                .put("key", "areas")
                .put("label", "侧边栏场景布局配置")
                .put("widget", "sidebar_layout_editor")
                .put("hint", "按场景（首页、分类/标签/搜索、友链、文章详情、通用默认）拖拽排序小工具并配置参数。");
        return definition(SettingKey.SIDEBAR_LAYOUT, SidebarTemplateData.buildDefaultSidebarLayoutNode(MAPPER),
                "外观设置", schema, "侧边栏小工具布局（支持多场景独立拖拽排序、启停与参数配置）");
    }

    private static SettingDefinition featureToggles() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "enable_comment").put("label", "开启评论").put("widget", "switch");
        fields.addObject().put("key", "enable_registration").put("label", "开启注册").put("widget", "switch");
        fields.addObject().put("key", "enable_user_post_submission").put("label", "允许用户投稿文章").put("widget", "switch");
        fields.addObject().put("key", "enable_ai_summary").put("label", "开启AI摘要").put("widget", "switch");

        ObjectNode value = MAPPER.createObjectNode();
        value.put("enable_comment", true);
        value.put("enable_registration", true);
        value.put("enable_user_post_submission", true);
        value.put("enable_ai_summary", true);
        return definition(SettingKey.FEATURE_TOGGLES, value, "功能设置", schema, "功能开关");
    }

    private static SettingDefinition amp() {
        ObjectNode schema = objectSchema();
        schema.putArray("fields").addObject()
                .put("key", "enabled")
                .put("label", "启用 AMP 页面")
                .put("widget", "switch")
                .put("hint", "关闭后不生成 AMP 页面，也不在文章页提供 AMP 链接；保存后立即生效。");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("enabled", staticBoolean("windblog.amp.enabled", true));
        return definition(SettingKey.AMP, value, "功能设置", schema, "AMP 页面功能设置");
    }

    private static SettingDefinition analyticsTracking() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "enabled").put("label", "启用访问分析").put("widget", "switch")
                .put("hint", "关闭后不向第三方分析服务加载脚本或发送访问事件。");
        fields.addObject().put("key", "scriptUrl").put("label", "追踪脚本 URL").put("widget", "input")
                .put("hint", "HTTPS 地址；本地开发可使用 http://localhost:8080/tracker.js。");
        fields.addObject().put("key", "siteId").put("label", "Lens 站点 ID").put("widget", "input")
                .put("hint", "从 SeeRay Lens 的站点集成页面复制 data-site-id 值。");
        fields.addObject().put("key", "requireConsent").put("label", "要求访客同意").put("widget", "switch")
                .put("hint", "与 SeeRay Lens 站点的需要同意策略保持一致；开启后访客选择前不会发送分析数据，并显示接受/拒绝弹窗。");
        fields.addObject().put("key", "heatmaps").put("label", "启用行为热图").put("widget", "switch")
                .put("hint", "仅发送 Lens 已配置采样的页面行为热图；关闭后不加载热图采集器。");
        fields.addObject().put("key", "webVitals").put("label", "启用 Web Vitals").put("widget", "switch")
                .put("hint", "发送当前页面的 LCP、INP、CLS 指标，不读取页面文本或表单值。");
        fields.addObject().put("key", "forms").put("label", "启用表单分析").put("widget", "switch")
                .put("hint", "仅统计显式标记 data-seeray-form 的表单；不会发送字段名、标签或输入值。");
        fields.addObject().put("key", "media").put("label", "启用媒体分析").put("widget", "switch")
                .put("hint", "仅统计显式标记 data-seeray-media 的音视频播放进度，不发送媒体地址或内容。");
        fields.addObject().put("key", "errors").put("label", "启用浏览器错误分析").put("widget", "switch")
                .put("hint", "发送 Lens 脱敏后的浏览器错误摘要；不发送完整堆栈、页面标题或访客标识。");
        fields.addObject().put("key", "tagManager").put("label", "启用 Tag Manager").put("widget", "switch")
                .put("hint", "加载 Lens 中为本站发布的标签容器；仅执行已发布且符合触发条件的标签。");
        fields.addObject().put("key", "experiments").put("label", "启用 A/B 实验").put("widget", "switch")
                .put("hint", "加载 Lens 实验定义；页面通过 data-seeray-experiment 标记申请变体。");
        fields.addObject().put("key", "privacyPreferences").put("label", "显示隐私偏好入口").put("widget", "switch")
                .put("hint", "在页脚提供 Lens 隐私偏好中心入口，允许访客随时修改同意选择。");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("enabled", false);
        value.put("scriptUrl", "");
        value.put("siteId", "");
        value.put("requireConsent", false);
        value.put("heatmaps", true);
        value.put("webVitals", false);
        value.put("forms", false);
        value.put("media", false);
        value.put("errors", false);
        value.put("tagManager", false);
        value.put("experiments", false);
        value.put("privacyPreferences", true);
        value.putObject("regions");
        return withRegion(definition(SettingKey.ANALYTICS_TRACKING, value, "访问分析", schema,
                "由数据库管理的 SeeRay Lens 追踪配置；保存后自动更新页面 CSP。"), RegionalKind.OBJECT_REGIONS);
    }

    private static SettingDefinition aiCommentAudit() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "prompt").put("label", "AI 审核提示词").put("widget", "textarea").put("required", true);
        fields.addObject().put("key", "autoAudit").put("label", "自动 AI 审核").put("widget", "switch");
        fields.addObject().put("key", "allowAutoDecision").put("label", "允许 AI 自动通过或拒绝").put("widget", "switch");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("prompt", CommentModerationPrompt.DEFAULT_PROMPT);
        value.put("autoAudit", true);
        value.put("allowAutoDecision", false);
        return definition(SettingKey.AI_COMMENT_AUDIT, value, "功能设置", schema, "AI 评论审核设置");
    }

    private static SettingDefinition aiUserProfileAudit() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "prompt").put("label", "资料文本审核提示词").put("widget", "textarea").put("required", true);
        fields.addObject().put("key", "avatarPrompt").put("label", "头像多模态审核提示词").put("widget", "textarea").put("required", true);
        fields.addObject().put("key", "autoAudit").put("label", "自动 AI 审核").put("widget", "switch");
        fields.addObject().put("key", "allowAutoDecision").put("label", "允许 AI 自动通过或拒绝").put("widget", "switch");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("prompt", UserProfileModerationPrompt.DEFAULT_TEXT_PROMPT);
        value.put("avatarPrompt", UserProfileModerationPrompt.DEFAULT_AVATAR_PROMPT);
        value.put("autoAudit", true);
        value.put("allowAutoDecision", false);
        return definition(SettingKey.AI_USER_PROFILE_AUDIT, value, "功能设置", schema, "AI 用户资料（昵称、简介、头像）审核设置");
    }

    private static SettingDefinition aiTranslation() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "prompt").put("label", "AI 翻译提示词")
                .put("widget", "textarea").put("required", true);
        fields.addObject().put("key", "defaultTargetLanguage").put("label", "默认目标语言")
                .put("widget", "input").put("hint", "例如 en-US；翻译管理页会以此作为默认目标语言。");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("prompt", TranslationPrompt.DEFAULT_PROMPT);
        value.put("defaultTargetLanguage", "en-US");
        return definition(SettingKey.AI_TRANSLATION, value, "功能设置", schema,
                "文章翻译提示词与默认目标语言。提示词会用于所有支持翻译的 AI 提供商和轮询组。");
    }

    private static SettingDefinition aiOperationConfigs() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        for (String[] operation : new String[][]{
                {"moderate", "评论审核 AI 配置"},
                {"profile_audit", "用户资料文本审核 AI 配置"},
                {"avatar_audit", "用户头像多模态审核 AI 配置"},
                {"summarize", "AI 摘要配置"},
                {"translate", "AI 翻译配置"},
                {"assistant", "写作助手 AI 配置"},
                {"topic", "话题功能 AI 配置"},
                {"slug", "Slug 翻译/生成 AI 配置"}
        }) {
            ObjectNode field = fields.addObject();
            field.put("key", operation[0]);
            field.put("label", operation[1]);
            field.put("widget", "select");
            field.put("hint", "topic".equals(operation[0])
                    ? "选择单个提供商或轮询组；留空时自动选择。话题生成、话题池和草稿回写全部由 AI API 完成。"
                    : ("slug".equals(operation[0])
                            ? "选择单个提供商或轮询组；留空时自动沿用翻译配置或自动选择。"
                            : "选择单个提供商或轮询组；留空时自动选择"));
            field.putArray("options");
        }
        ObjectNode value = MAPPER.createObjectNode();
        value.put("moderate", "");
        value.put("profile_audit", "");
        value.put("avatar_audit", "");
        value.put("summarize", "");
        value.put("translate", "");
        value.put("assistant", "");
        value.put("topic", "");
        value.put("slug", "");
        return definition(SettingKey.AI_OPERATION_CONFIGS, value, "功能设置", schema,
                "分别指定 AI 评论审核、摘要、翻译、话题和 Slug 生成功能使用的提供商或轮询组。");
    }

    private static SettingDefinition elasticsearch() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject()
                .put("key", "enabled")
                .put("label", "启用 Elasticsearch")
                .put("widget", "switch")
                .put("section", "基础设置");
        fields.addObject()
                .put("key", "hosts")
                .put("label", "连接地址")
                .put("widget", "input")
                .put("required", true)
                .put("section", "基础设置")
                .put("hint", "例如：http://elasticsearch:9200");
        fields.addObject().put("key", "username").put("label", "用户名").put("widget", "input").put("section", "基础设置");
        fields.addObject().put("key", "password").put("label", "密码").put("widget", "password").put("section", "基础设置");
        fields.addObject()
                .put("key", "timeout_seconds")
                .put("label", "连接超时（秒）")
                .put("widget", "number")
                .put("section", "基础设置");
        ObjectNode analyzerField = fields.addObject();
        analyzerField.put("key", "analyzer");
        analyzerField.put("label", "分词器");
        analyzerField.put("widget", "select");
        analyzerField.put("section", "基础设置");
        ArrayNode analyzerOptions = analyzerField.putArray("options");
        analyzerOptions.addObject().put("label", "IK 最大词粒度").put("value", "ik_max_word");
        analyzerOptions.addObject().put("label", "IK 智能分词").put("value", "ik_smart");
        analyzerOptions.addObject().put("label", "标准分词器").put("value", "standard");
        fields.addObject().put("key", "synonyms").put("label", "同义词规则").put("widget", "synonym_cards").put("section", "同义词");

        ObjectNode value = MAPPER.createObjectNode();
        value.put("enabled", true);
        value.put("hosts", staticString("elasticsearch.hosts", "http://127.0.0.1:9200"));
        value.put("username", staticString("elasticsearch.username", ""));
        value.put("password", staticString("elasticsearch.password", ""));
        value.put("timeout_seconds", staticInt("elasticsearch.health-check.timeout-seconds", 5));
        value.put("analyzer", "ik_max_word");
        value.putArray("synonyms");
        return definition(SettingKey.ELASTICSEARCH, value, "Elasticsearch", schema,
                "Elasticsearch 搜索、分词与同义词设置", SettingLevel.CRITICAL,
                false, Set.of("password"), RegionalKind.NONE);
    }

    private static SettingDefinition securityNetwork() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "trusted_proxy_cidrs").put("label", "可信代理 CIDR").put("widget", "textarea").put("required", true);
        fields.addObject().put("key", "client_ip_header").put("label", "客户端 IP 请求头").put("widget", "input").put("required", true)
                .put("hint", "支持任意自定义请求头，例如 X-Forwarded-For、Forwarded、X-Real-IP、EO-Connecting-IP（EdgeOne）、CF-Connecting-IP（Cloudflare）。多值头按逗号分隔，可带端口。名称不合法时回退为 X-Forwarded-For。");
        ObjectNode sourceField = fields.addObject();
        sourceField.put("key", "client_ip_source");
        sourceField.put("label", "客户端 IP 获取方式");
        sourceField.put("widget", "select");
        sourceField.put("required", true);
        sourceField.put("hint", "自动：直连来源在可信代理 CIDR 内时读取请求头；网络连接：始终使用 TCP 连接地址；HTTP 头：始终读取请求头，仅在源站只接受可信代理回源时使用，否则请求头可被伪造。读取请求头时从右向左跳过可信代理 CIDR 内的地址，取第一个不可信地址作为访客 IP，因此 CDN 回源地址需加入可信 CIDR，或改用 CDN 提供的单值头。");
        ArrayNode sourceOptions = sourceField.putArray("options");
        sourceOptions.addObject().put("label", "自动（可信代理时读请求头）").put("value", "auto");
        sourceOptions.addObject().put("label", "仅网络连接地址").put("value", "network");
        sourceOptions.addObject().put("label", "仅 HTTP 请求头").put("value", "header");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("trusted_proxy_cidrs", "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16");
        value.put("client_ip_header", "X-Forwarded-For");
        value.put("client_ip_source", "auto");
        return definition(SettingKey.SECURITY_NETWORK, value, "安全设置", schema,
                "可信代理与客户端 IP 解析设置", SettingLevel.CRITICAL);
    }

    private static SettingDefinition commentSecurity() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        ObjectNode difficultyField = fields.addObject();
        difficultyField.put("key", "pow_difficulty");
        difficultyField.put("label", "评论工作量证明难度（前导零位数）");
        difficultyField.put("widget", "number");
        difficultyField.put("required", true);
        difficultyField.put("hint", "每增加 1 位，客户端计算量翻倍；建议 14-18，16 约等于普通手机 0.5-1 秒。");
        fields.addObject().put("key", "challenge_ttl_seconds").put("label", "评论验证有效期（秒）").put("widget", "number").put("required", true);
        ObjectNode value = MAPPER.createObjectNode();
        value.put("pow_difficulty", 16);
        value.put("challenge_ttl_seconds", 300);
        return definition(SettingKey.COMMENT_SECURITY, value, "安全设置", schema,
                "评论无感工作量证明验证设置", SettingLevel.CRITICAL);
    }

    private static SettingDefinition seo() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "google_site_verification").put("widget", "input").put("label", "Google Search Console 验证码");
        fields.addObject().put("key", "index_enabled").put("widget", "switch").put("label", "允许搜索引擎收录公开页面");
        fields.addObject().put("key", "follow_enabled").put("widget", "switch").put("label", "允许跟踪页面链接");
        fields.addObject().put("key", "internal_link_graph_enabled").put("widget", "switch").put("label", "启用内部链接图")
                .put("hint", "前台显示当前文章的直接引用关系；后台支持递归分析。");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("google_site_verification", "");
        value.put("index_enabled", true);
        value.put("follow_enabled", true);
        value.put("internal_link_graph_enabled", false);
        return definition(SettingKey.SEO, value, "seo", schema, "SEO 收录控制与站点验证");
    }

    private static SettingDefinition legalTerms() {
        ObjectNode schema = objectSchema();
        schema.putArray("fields").addObject()
                .put("key", "html")
                .put("label", "用户协议内容（HTML）")
                .put("widget", "textarea")
                .put("required", true);
        ObjectNode value = MAPPER.createObjectNode();
        value.put("html", DEFAULT_TERMS);
        return definition(SettingKey.LEGAL_TERMS, value, "legal", schema, "公共用户协议内容");
    }

    private static SettingDefinition legalPrivacy() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "html").put("label", "隐私政策内容（HTML）").put("widget", "textarea").put("required", true);
        fields.addObject().put("key", "showAnalyticsOptOut").put("label", "显示统计退出表单").put("widget", "switch")
                .put("hint", "在首页隐私政策页面显示选择退出表单，访客可让当前浏览器停止发送 SeeRay Lens 统计数据。");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("html", DEFAULT_PRIVACY);
        value.put("showAnalyticsOptOut", false);
        return definition(SettingKey.LEGAL_PRIVACY, value, "legal", schema, "公共隐私政策内容");
    }

    private static SettingDefinition gamification() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "level_up_reward").put("label", "升级奖励积分").put("widget", "number");
        fields.addObject().put("key", "comment_exp_reward").put("label", "评论经验奖励").put("widget", "number");
        fields.addObject().put("key", "checkin_exp_reward").put("label", "签到经验奖励").put("widget", "number");
        fields.addObject().put("key", "post_exp_reward").put("label", "投稿经验奖励").put("widget", "number");
        fields.addObject().put("key", "like_exp_reward").put("label", "点赞经验奖励").put("widget", "number");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("level_up_reward", 50);
        value.put("comment_exp_reward", 10);
        value.put("checkin_exp_reward", 10);
        value.put("post_exp_reward", 10);
        value.put("like_exp_reward", 10);
        return definition(SettingKey.GAMIFICATION, value, "功能设置", schema, "等级与行为经验奖励设置");
    }

    private static SettingDefinition dailyCheckInRewards() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "points").put("label", "积分奖励").put("widget", "input").put("required", true);
        fields.addObject().put("key", "experience").put("label", "经验奖励").put("widget", "input");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("points", 10);
        value.putArray("items");
        value.put("experience", 5);
        return definition(SettingKey.DAILY_CHECK_IN_REWARDS, value, "wallet", schema, "每日签到基础奖励配置");
    }

    private static SettingDefinition googleAdsense() {
        ObjectNode schema = objectSchema();
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("key", "enabled").put("label", "启用 Google AdSense").put("widget", "switch");
        fields.addObject().put("key", "clientId").put("label", "发布商 ID").put("widget", "input");
        fields.addObject().put("key", "autoAds").put("label", "启用自动广告（AdSense 控制台配置）").put("widget", "switch");
        fields.addObject().put("key", "lazyLoad").put("label", "广告块懒加载").put("widget", "switch");
        fields.addObject().put("key", "rootMargin").put("label", "进入视口前预加载距离（像素，0–2000）").put("widget", "number");
        fields.addObject().put("key", "collapseUnfilled").put("label", "折叠未填充广告").put("widget", "switch");
        fields.addObject().put("key", "blocks").put("label", "广告块 JSON 数组").put("widget", "textarea")
                .put("hint", "示例：[{\"slot\":\"1234567890\",\"placement\":\"sidebar\",\"format\":\"auto\",\"responsive\":true,\"minHeight\":250,\"device\":\"all\"}]。位置：content_top、content_bottom、sidebar；device：all、mobile、desktop；固定尺寸使用 format=fixed、width、height；fluid 支持 layout、layoutKey。");
        ObjectNode value = MAPPER.createObjectNode();
        value.put("enabled", false);
        value.put("clientId", "ca-pub-4006222603298393");
        value.put("autoAds", false);
        value.put("lazyLoad", true);
        value.put("rootMargin", 300);
        value.put("collapseUnfilled", true);
        value.put("blocks", "[]");
        return definition(SettingKey.GOOGLE_ADSENSE, value, "advertising", schema, "Google AdSense 广告、懒加载与广告块设置");
    }

    private static SettingDefinition definition(SettingKey key, JsonNode defaultValue, String group,
                                                JsonNode uiSchema, String description) {
        return definition(key, defaultValue, group, uiSchema, description, SettingLevel.NORMAL,
                false, Set.of(), RegionalKind.NONE);
    }

    private static SettingDefinition definition(SettingKey key, JsonNode defaultValue, String group,
                                                JsonNode uiSchema, String description, SettingLevel level) {
        return definition(key, defaultValue, group, uiSchema, description, level,
                false, Set.of(), RegionalKind.NONE);
    }

    private static SettingDefinition definition(SettingKey key, JsonNode defaultValue, String group,
                                                JsonNode uiSchema, String description, SettingLevel level,
                                                boolean secret, Set<String> secretFields,
                                                RegionalKind regionalKind) {
        return new SettingDefinition(key, defaultValue, "object", group, uiSchema, description, level,
                secret, new LinkedHashSet<>(secretFields), regionalKind);
    }

    private static SettingDefinition withRegion(SettingDefinition definition, RegionalKind regionalKind) {
        return new SettingDefinition(definition.key(), definition.defaultValue(), definition.configType(),
                definition.group(), definition.uiSchema(), definition.description(), definition.level(),
                definition.secret(), definition.secretFields(), regionalKind);
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    private static String staticString(String name, String defaultValue) {
        try {
            return ConfigProvider.getConfig().getOptionalValue(name, String.class).orElse(defaultValue);
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static boolean staticBoolean(String name, boolean defaultValue) {
        try {
            return ConfigProvider.getConfig().getOptionalValue(name, Boolean.class).orElse(defaultValue);
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static int staticInt(String name, int defaultValue) {
        try {
            return ConfigProvider.getConfig().getOptionalValue(name, Integer.class).orElse(defaultValue);
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static final String DEFAULT_TERMS = "<h1>用户协议</h1><p>欢迎使用 WindBlog。注册和使用本站服务即表示您同意遵守本协议以及适用的法律法规。</p><h2>账号</h2><p>您应提供真实、准确的注册信息并妥善保管账号凭据。不得冒用他人身份或利用本站从事违法活动。</p><h2>内容</h2><p>您对自行发布的内容负责，不得发布侵犯他人权利、恶意程序或违反法律法规的内容。我们可能依法处理违规内容。</p><h2>服务变更</h2><p>在必要时我们会调整功能或维护服务，并尽量提前告知重大变化。</p>";
    private static final String DEFAULT_PRIVACY = "<h1>隐私政策</h1><p>我们只在提供账号、评论、订阅和安全功能所必需的范围内处理个人信息。</p><h2>收集的信息</h2><p>注册时会保存用户名、邮箱和密码哈希。密码重置令牌只保存哈希值，并在使用或过期后失效。</p><h2>邮件</h2><p>邮箱验证、密码重置和您主动订阅的通知会通过站点配置的邮件服务发送。我们不会在页面或日志中展示密码和令牌。</p><h2>您的权利</h2><p>如需更正或删除账号信息，请通过站点公布的联系方式联系管理员。</p>";
}
