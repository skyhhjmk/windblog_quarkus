package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.constant.LanguageConstant;
import io.quarkus.qute.TemplateData;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 语言助手类
 */
@TemplateData
public final class LanguageHelper {

    public LanguageHelper() {
    }

    /**
     * @param rawLang 原始语言
     * @return 标准化后的语言
     */
    public static String normalizeToSupportedLang(String rawLang) {
        if (rawLang == null || rawLang.isBlank()) {
            return null;
        }

        String normalized = rawLang.trim().replace('_', '-');

        if (LanguageConstant.SUPPORT_LANGS.contains(normalized)) {
            return normalized;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) {
            return LanguageConstant.LANG_ZH_CN;
        }
        if (lower.startsWith("en")) {
            return LanguageConstant.LANG_EN_US;
        }
        return null;
    }

    /**
     * 从路径中解析语言
     *
     * @param path 路径
     * @return 语言
     */
    public static String resolveLangFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slashIndex = trimmed.indexOf('/');
        String firstSegment = slashIndex >= 0 ? trimmed.substring(0, slashIndex) : trimmed;
        return normalizeToSupportedLang(firstSegment);
    }

    /**
     * 从 localized map 中解析 localized value
     *
     * @param localized     localized map
     * @param preferredLang preferred language
     * @return localized value
     */
    public static String resolveLocalizedValue(Map<String, String> localized, String preferredLang) {
        if (localized == null || localized.isEmpty()) {
            return null;
        }

        for (String candidate : languageCandidates(preferredLang)) {
            String value = localized.get(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        for (String value : localized.values()) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Set<String> languageCandidates(String lang) {
        Set<String> candidates = new LinkedHashSet<>();

        String preferred = normalizeToSupportedLang(lang);
        if (preferred != null) {
            candidates.add(preferred);
            candidates.add(toShortLang(preferred));
        }

        candidates.add(LanguageConstant.DEFAULT_LANG);
        candidates.add(toShortLang(LanguageConstant.DEFAULT_LANG));
        return candidates;
    }

    private static String toShortLang(String lang) {
        int index = lang.indexOf('-');
        return index > 0 ? lang.substring(0, index) : lang;
    }

    public String resolveLocalizedValue(Object localized, String preferredLang) {
        if (localized instanceof Map) {
            return resolveLocalizedValue((Map<String, String>) localized, preferredLang);
        }
        return null;
    }
}
