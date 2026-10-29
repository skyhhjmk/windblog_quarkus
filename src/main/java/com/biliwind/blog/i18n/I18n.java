package com.biliwind.blog.i18n;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped translation helper exposed to Qute as {@code i18n} and to the
 * browser as a JSON bundle under {@code window.i18n}. Messages live in
 * {@code i18n/messages_<locale>.properties}; missing keys fall back to zh-cn
 * and then to the key itself so a half-translated page never renders blank.
 */
@Unremovable
@RequestScoped
public class I18n {

    private static final String BASE = "i18n/messages_";
    private static final String JS_PREFIX = "js.";
    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();
    private static final Map<String, String> JS_JSON = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper mapper;

    public String t(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String lang = currentLanguage();
        String value = bundle(lang).get(key);
        if (value == null || value.isEmpty()) {
            value = bundle(LanguageConstant.LANG_ZH_CN).get(key);
        }
        return value == null ? key : value;
    }

    public String t(String key, String argument) {
        return format(key, argument);
    }

    public String format(String key, Object argument) {
        String pattern = t(key);
        try {
            return new MessageFormat(pattern, Locale.forLanguageTag(currentLanguage()))
                    .format(new Object[]{argument});
        } catch (IllegalArgumentException exception) {
            return pattern;
        }
    }

    public String format(String key, Object first, Object second) {
        String pattern = t(key);
        try {
            return new MessageFormat(pattern, Locale.forLanguageTag(currentLanguage()))
                    .format(new Object[]{first, second});
        } catch (IllegalArgumentException exception) {
            return pattern;
        }
    }

    public String getJsJson() {
        String lang = currentLanguage();
        return JS_JSON.computeIfAbsent(lang, this::buildJsJson);
    }

    public String getLanguage() {
        return currentLanguage();
    }

    private String buildJsJson(String lang) {
        Map<String, String> messages = new LinkedHashMap<>();
        bundle(lang).forEach((key, value) -> {
            if (key.startsWith(JS_PREFIX)) {
                messages.put(key.substring(JS_PREFIX.length()), value);
            }
        });
        // Fill missing JS keys from the default language so partial translations
        // never leave an undefined string in the browser bundle.
        bundle(LanguageConstant.LANG_ZH_CN).forEach((key, value) -> {
            if (key.startsWith(JS_PREFIX)) {
                messages.putIfAbsent(key.substring(JS_PREFIX.length()), value);
            }
        });
        try {
            return mapper.writeValueAsString(messages);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String currentLanguage() {
        var context = LanguageRequestResolver.current();
        String normalized = LanguageHelper.normalizeToSupportedLang(
                context == null ? null : context.getLang());
        return normalized == null ? LanguageConstant.DEFAULT_LANG : normalized;
    }

    private Map<String, String> bundle(String lang) {
        String normalized = LanguageHelper.normalizeToSupportedLang(lang);
        if (normalized == null) {
            normalized = LanguageConstant.DEFAULT_LANG;
        }
        return BUNDLES.computeIfAbsent(normalized, this::load);
    }

    private Map<String, String> load(String lang) {
        String suffix = LanguageConstant.LANG_EN_US.equalsIgnoreCase(lang) ? "en_US" : "zh_CN";
        String resource = BASE + suffix + ".properties";
        Properties properties = new Properties();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = I18n.class.getClassLoader();
        }
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input != null) {
                try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (Exception ignored) {
            // Fallback to zh-cn / key names.
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            result.put(name, properties.getProperty(name));
        }
        return result;
    }
}
