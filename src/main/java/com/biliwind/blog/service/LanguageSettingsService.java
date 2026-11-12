package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.SystemSettingHistory;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

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

    public static final String SETTING_KEY = SettingKey.SITE_LANGUAGE.key();
    public static final String FIELD_DEFAULT_LANGUAGE = "default_language";
    private static final String REGIONS = "regions";
    private static final String GLOBAL = "global";

    @Inject
    SettingsReader settings;

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
        return normalize(settings.language(region));
    }

    /**
     * Cache-only variant safe for the Vert.x IO thread.
     */
    public String defaultLanguageCached(BlogRegion region) {
        return normalize(settings.languageCached(region));
    }

    private String normalize(String configured) {
        String normalized = LanguageHelper.normalizeToSupportedLang(configured);
        return normalized != null ? normalized : LanguageConstant.DEFAULT_LANG;
    }

    public boolean hasRegionOverride(BlogRegion region) {
        return settings.hasLanguageOverride(region);
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

    public record RegionLanguageSetting(String region,
                                        String displayName,
                                        String defaultLanguage,
                                        boolean overridden) {
    }
}
