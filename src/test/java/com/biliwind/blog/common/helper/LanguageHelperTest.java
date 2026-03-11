package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.constant.LanguageConstant;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class LanguageHelperTest {

    @Test
    void shouldNormalizeLanguageAliases() {
        assertEquals(LanguageConstant.LANG_EN_US, LanguageHelper.normalizeToSupportedLang("en"));
        assertEquals(LanguageConstant.LANG_EN_US, LanguageHelper.normalizeToSupportedLang("EN_us"));
        assertEquals(LanguageConstant.LANG_ZH_CN, LanguageHelper.normalizeToSupportedLang("zh"));
        assertNull(LanguageHelper.normalizeToSupportedLang("fr"));
    }

    @Test
    void shouldResolveLanguageFromPath() {
        assertEquals(LanguageConstant.LANG_EN_US, LanguageHelper.resolveLangFromPath("/en/post/hello"));
        assertEquals(LanguageConstant.LANG_ZH_CN, LanguageHelper.resolveLangFromPath("zh-CN/post/hello"));
        assertNull(LanguageHelper.resolveLangFromPath("/post/hello"));
    }

    @Test
    void shouldFallbackToDefaultLanguageValue() {
        Map<String, String> localized = new LinkedHashMap<>();
        localized.put("zh-CN", "中文标题");
        localized.put("en-US", "English Title");

        assertEquals("English Title", LanguageHelper.resolveLocalizedValue(localized, "en-US"));
        assertEquals("中文标题", LanguageHelper.resolveLocalizedValue(localized, "ja-JP"));
    }

    @Test
    void shouldFallbackToFirstNonBlankWhenDefaultMissing() {
        Map<String, String> localized = new LinkedHashMap<>();
        localized.put("en-US", "English Title");
        localized.put("zh-CN", "");

        assertEquals("English Title", LanguageHelper.resolveLocalizedValue(localized, "ja-JP"));
    }
}
