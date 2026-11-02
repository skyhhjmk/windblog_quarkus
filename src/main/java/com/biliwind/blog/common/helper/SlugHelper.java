package com.biliwind.blog.common.helper;

import com.ibm.icu.text.Transliterator;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Slug 助手类，用于自动生成 slug
 */
public final class SlugHelper {

    // ICU Transliterator instances are not thread-safe; keep one per thread
    // because slug previews are generated from HTTP request threads.
    private static final ThreadLocal<Transliterator> TRANSLITERATOR =
            ThreadLocal.withInitial(() -> Transliterator.getInstance("Any-Latin; Latin-ASCII; Any-Lower"));
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern HYPHENS = Pattern.compile("^-+|-+$");
    private static final Pattern TRAILING_HYPHEN = Pattern.compile("-+$");

    /** posts.slug / tags.slug / categories.slug are varchar(160). */
    public static final int MAX_LENGTH = 160;

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
        String transliterated = TRANSLITERATOR.get().transliterate(input);

        // 2. 将非字母数字字符替换为连字符
        String slug = NON_ALPHANUMERIC.matcher(transliterated).replaceAll("-");

        // 3. 去除首尾连字符
        slug = HYPHENS.matcher(slug).replaceAll("");

        // 4. 截断到数据库列长度；若正好切在单词中间，则退回上一个连字符
        if (slug.length() > MAX_LENGTH) {
            String truncated = slug.substring(0, MAX_LENGTH);
            if (slug.charAt(MAX_LENGTH) != '-') {
                int lastHyphen = truncated.lastIndexOf('-');
                if (lastHyphen > 0) {
                    truncated = truncated.substring(0, lastHyphen);
                }
            }
            slug = TRAILING_HYPHEN.matcher(truncated).replaceAll("");
        }
        return slug;
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
