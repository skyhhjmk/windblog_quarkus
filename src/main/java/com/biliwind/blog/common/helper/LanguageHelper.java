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
     * Resolves the best supported language from a full Accept-Language header,
     * honouring q-values instead of blindly taking the first tag.
     */
    public static String resolveFromAcceptLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }
        String bestLang = null;
        double bestQuality = -1;
        int bestIndex = Integer.MAX_VALUE;
        String[] parts = acceptLanguage.split(",");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index].trim();
            if (part.isEmpty()) {
                continue;
            }
            String tag = part;
            double quality = 1.0;
            int semicolon = part.indexOf(';');
            if (semicolon >= 0) {
                tag = part.substring(0, semicolon).trim();
                String params = part.substring(semicolon + 1);
                for (String param : params.split(";")) {
                    String trimmed = param.trim();
                    if (trimmed.regionMatches(true, 0, "q=", 0, 2)) {
                        try {
                            quality = Double.parseDouble(trimmed.substring(2).trim());
                        } catch (NumberFormatException ignored) {
                            quality = 0;
                        }
                    }
                }
            }
            if (tag.isEmpty() || "*".equals(tag)) {
                continue;
            }
            String normalized = normalizeToSupportedLang(tag);
            if (normalized == null) {
                continue;
            }
            if (quality > bestQuality || (quality == bestQuality && index < bestIndex)) {
                bestLang = normalized;
                bestQuality = quality;
                bestIndex = index;
            }
        }
        return bestLang;
    }

    /**
     * Returns the URL prefix for a language on a site whose region default is
     * {@code defaultLang}: the default language stays unprefixed.
     */
    public static String prefixFor(String lang, String defaultLang) {
        String normalized = normalizeToSupportedLang(lang);
        String normalizedDefault = normalizeToSupportedLang(defaultLang);
        if (normalized == null || normalized.equalsIgnoreCase(normalizedDefault)) {
            return "";
        }
        return "/" + normalized;
    }

    /**
     * Applies the language prefix to a site-root-relative path.
     */
    public static String localizedPath(String path, String lang, String defaultLang) {
        String prefix = prefixFor(lang, defaultLang);
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if (prefix.isEmpty()) {
            return normalizedPath;
        }
        return normalizedPath.equals("/") ? prefix + "/" : prefix + normalizedPath;
    }

    /**
     * Removes a leading /zh-cn or /en-US (or short alias) from a path.
     */
    public static String stripLanguagePrefix(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        int secondSlash = normalizedPath.indexOf('/', 1);
        String firstSegment = secondSlash < 0
                ? normalizedPath.substring(1)
                : normalizedPath.substring(1, secondSlash);
        if (normalizeToSupportedLang(firstSegment) == null) {
            return normalizedPath;
        }
        if (secondSlash < 0) {
            return "/";
        }
        return normalizedPath.substring(secondSlash);
    }

    /**
     * Returns the normalized language actually used for a localized map, or the
     * default language when no entry matched.
     */
    public static String resolveLocalizedLanguage(Map<String, String> localized, String preferredLang) {
        if (localized == null || localized.isEmpty()) {
            return normalizeToSupportedLang(preferredLang);
        }
        for (String candidate : languageCandidates(preferredLang)) {
            String value = localized.get(candidate);
            if (value != null && !value.isBlank()) {
                return normalizeToSupportedLang(candidate);
            }
        }
        for (Map.Entry<String, String> entry : localized.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                String normalized = normalizeToSupportedLang(entry.getKey());
                return normalized != null ? normalized : LanguageConstant.DEFAULT_LANG;
            }
        }
        return LanguageConstant.DEFAULT_LANG;
    }

    /**
     * 从 localized map 中解析 localized value
     * @param localized  localized map
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

    @SuppressWarnings("unchecked")
    public String resolveLocalizedValue(Object localized, String preferredLang) {
        if (localized instanceof Map) {
            return resolveLocalizedValue((Map<String, String>) localized, preferredLang);
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
}
