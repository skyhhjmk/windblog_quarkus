package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.SystemSettingHistory;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Region-scoped default language. Stored in the {@code site_language} setting:
 * <pre>
 * {
 *   "default_language": "zh-cn",
 *   "regions": { "global": "en-US", "cn": "zh-cn" }
 * }
 * </pre>
 * A region entry wins over the global region entry, which wins over the root
 * fallback. Region entries can be plain language tags or objects containing
 * {@code default_language} so that the shape can grow later.
 */
@ApplicationScoped
public class LanguageSettingsService {

    public static final String SETTING_KEY = "site_language";
    public static final String FIELD_DEFAULT_LANGUAGE = "default_language";
    private static final String REGIONS = "regions";
    private static final String GLOBAL = "global";

    @Inject
    ConfigManager configManager;

    @Inject
    ObjectMapper mapper;

    @Inject
    Event<ConfigChangedEvent> configChangedEvent;

    @Inject
    Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    @Inject
    AuditService auditService;

    /**
     * Effective default language for a region. Never returns null; falls back
     * to the site default and then to zh-cn.
     */
    public String defaultLanguage(BlogRegion region) {
        return resolve(configManager.get(SETTING_KEY), region);
    }

    /**
     * Cache-only variant safe for the Vert.x IO thread.
     */
    public String defaultLanguageCached(BlogRegion region) {
        return resolve(configManager.getLocal(SETTING_KEY), region);
    }

    private String resolve(JsonNode value, BlogRegion region) {
        BlogRegion effective = region == null ? BlogRegion.GLOBAL : region;
        String configured = readRegionLanguage(value, effective.getCode());
        if (configured == null && !BlogRegion.GLOBAL.equals(effective)) {
            configured = readRegionLanguage(value, GLOBAL);
        }
        if (configured == null && value != null) {
            configured = text(value.get(FIELD_DEFAULT_LANGUAGE));
        }
        String normalized = LanguageHelper.normalizeToSupportedLang(configured);
        return normalized != null ? normalized : LanguageConstant.DEFAULT_LANG;
    }

    public boolean hasRegionOverride(BlogRegion region) {
        return readRegionLanguage(configManager.get(SETTING_KEY),
                region == null ? GLOBAL : region.getCode()) != null;
    }

    public List<RegionLanguageSetting> listSettings() {
        List<RegionLanguageSetting> settings = new ArrayList<>();
        for (BlogRegion region : BlogRegion.values()) {
            settings.add(new RegionLanguageSetting(
                    region.getCode(),
                    region.getDisplayName(),
                    defaultLanguage(region),
                    hasRegionOverride(region)));
        }
        return settings;
    }

    @Transactional
    public RegionLanguageSetting updateDefaultLanguage(BlogRegion region,
                                                       String language,
                                                       Long operatorId,
                                                       String reason) {
        BlogRegion effective = region == null ? BlogRegion.GLOBAL : region;
        String normalized = LanguageHelper.normalizeToSupportedLang(language);
        if (normalized == null || !LanguageConstant.SUPPORT_LANGS.contains(normalized)) {
            throw new BadRequestException("不支持的语言: " + language);
        }

        SystemSetting setting = SystemSetting.findByKey(SETTING_KEY);
        if (setting == null) {
            setting = new SystemSetting();
            setting.configKey = SETTING_KEY;
            setting.configType = "object";
            setting.groupName = "基础设置";
            setting.description = "多区域默认语言设置";
            setting.uiSchema = mapper.createObjectNode();
            setting.configValue = defaultConfig();
            setting.persist();
        }

        ObjectNode value = setting.configValue != null && setting.configValue.isObject()
                ? ((ObjectNode) setting.configValue).deepCopy()
                : defaultConfig();
        ObjectNode regions = value.withObject("/" + REGIONS);
        regions.put(effective.getCode(), normalized);
        if (!value.hasNonNull(FIELD_DEFAULT_LANGUAGE)) {
            value.put(FIELD_DEFAULT_LANGUAGE, LanguageConstant.DEFAULT_LANG);
        }

        SystemSettingHistory history = new SystemSettingHistory();
        history.settingId = setting.id;
        history.configKey = setting.configKey;
        history.configValue = setting.configValue;
        history.version = setting.version;
        history.operatorId = operatorId;
        history.changeReason = reason;
        history.persist();

        setting.configValue = value;
        setting.version = setting.version == null ? 1 : setting.version + 1;
        setting.persist();

        configChangedEvent.fire(new ConfigChangedEvent(SETTING_KEY, value));
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent(
                "SYSTEM_SETTING", setting.id, "UPSERT"));
        auditService.log("site_language", effective.getCode(), "update",
                null, java.util.Map.of("region", effective.getCode(), "defaultLanguage", normalized));

        return new RegionLanguageSetting(effective.getCode(), effective.getDisplayName(),
                normalized, true);
    }

    /** Builds the initial/default configuration used when the key is missing. */
    public ObjectNode defaultConfig() {
        ObjectNode value = mapper.createObjectNode();
        value.put(FIELD_DEFAULT_LANGUAGE, LanguageConstant.DEFAULT_LANG);
        ObjectNode regions = value.putObject(REGIONS);
        regions.put(GLOBAL, LanguageConstant.LANG_EN_US);
        regions.put(BlogRegion.CN.getCode(), LanguageConstant.LANG_ZH_CN);
        regions.put(BlogRegion.HK.getCode(), LanguageConstant.LANG_ZH_CN);
        regions.put(BlogRegion.TW.getCode(), LanguageConstant.LANG_ZH_CN);
        return value;
    }

    private String readRegionLanguage(JsonNode value, String regionCode) {
        if (value == null || regionCode == null) {
            return null;
        }
        JsonNode regions = value.get(REGIONS);
        if (regions == null || !regions.isObject()) {
            return null;
        }
        JsonNode entry = regions.get(regionCode.toLowerCase(Locale.ROOT));
        if (entry == null) {
            return null;
        }
        if (entry.isTextual()) {
            return entry.asText();
        }
        if (entry.isObject()) {
            return text(entry.get(FIELD_DEFAULT_LANGUAGE));
        }
        return null;
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    public record RegionLanguageSetting(String region,
                                        String displayName,
                                        String defaultLanguage,
                                        boolean overridden) {
    }
}
