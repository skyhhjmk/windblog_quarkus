package com.biliwind.blog.service.settings;

import java.util.HashMap;
import java.util.Map;

/** Every database-backed setting key. Adding a setting starts here. */
public enum SettingKey {
    SITE_INFO("site_info"),
    SITE_FOOTER("site_footer"),
    SITE_LANGUAGE("site_language"),
    APPEARANCE("appearance"),
    SOCIAL_LINKS("social_links"),
    SIDEBAR_LAYOUT("sidebar_layout"),
    FEATURE_TOGGLES("feature_toggles"),
    AMP("amp"),
    ANALYTICS_TRACKING("analytics_tracking"),
    AI_COMMENT_AUDIT("ai_comment_audit"),
    AI_TRANSLATION("ai_translation"),
    AI_OPERATION_CONFIGS("ai_operation_configs"),
    ELASTICSEARCH("elasticsearch"),
    SECURITY_NETWORK("security_network"),
    COMMENT_SECURITY("comment_security"),
    SEO("seo"),
    LEGAL_TERMS("legal_terms"),
    LEGAL_PRIVACY("legal_privacy"),
    GAMIFICATION("gamification"),
    DAILY_CHECK_IN_REWARDS("daily_check_in_rewards"),
    GOOGLE_ADSENSE("google_adsense");

    private static final Map<String, SettingKey> BY_KEY = new HashMap<>();

    static {
        for (SettingKey key : values()) {
            BY_KEY.put(key.key, key);
        }
    }

    private final String key;

    SettingKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static SettingKey fromKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }
}
