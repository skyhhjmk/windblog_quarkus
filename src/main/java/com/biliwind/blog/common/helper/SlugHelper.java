package com.biliwind.blog.common.helper;

import com.ibm.icu.text.Transliterator;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Slug 助手类，用于自动生成 slug
 */
public final class SlugHelper {

    private static final Transliterator TRANSLITERATOR = Transliterator.getInstance("Any-Latin; Latin-ASCII; Any-Lower");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern HYPHENS = Pattern.compile("^-+|-+$");

    private SlugHelper() {
    }

    /**
     * 将字符串转换为 slug
     *
     * @param input 输入字符串
     * @return slug
     */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        // 1. 使用 ICU4J 进行音译（例如：中文 -> 拼音，且转为 ASCII）
        String transliterated = TRANSLITERATOR.transliterate(input);

        // 2. 将非字母数字字符替换为连字符
        String slug = NON_ALPHANUMERIC.matcher(transliterated).replaceAll("-");

        // 3. 去除首尾连字符
        return HYPHENS.matcher(slug).replaceAll("");
    }

    /**
     * 从多语言 Map 中解析并生成 slug
     *
     * @param localized     多语言 Map
     * @param preferredLang 首选语言
     * @return slug
     */
    public static String slugify(Map<String, String> localized, String preferredLang) {
        String value = LanguageHelper.resolveLocalizedValue(localized, preferredLang);
        return slugify(value);
    }

    /**
     * 从多语言 Map 中自动生成 slug（默认语言）
     *
     * @param localized 多语言 Map
     * @return slug
     */
    public static String slugify(Map<String, String> localized) {
        return slugify(localized, null);
    }
}
