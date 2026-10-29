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
    void shouldHonourAcceptLanguageQualityValues() {
        assertEquals(LanguageConstant.LANG_EN_US,
                LanguageHelper.resolveFromAcceptLanguage("fr-FR,en-US;q=0.9,zh-CN;q=0.4"));
        assertEquals(LanguageConstant.LANG_ZH_CN,
                LanguageHelper.resolveFromAcceptLanguage("en-US;q=0.2, zh-CN;q=0.9"));
        assertEquals(LanguageConstant.LANG_EN_US,
                LanguageHelper.resolveFromAcceptLanguage("de-DE, en-GB;q=0.7"));
        assertNull(LanguageHelper.resolveFromAcceptLanguage("fr-FR,de-DE;q=0.8"));
    }

    @Test
    void shouldBuildRegionAwareLanguagePaths() {
        assertEquals("/zh-cn/post/x", LanguageHelper.localizedPath("/post/x", "zh-cn", "en-US"));
        assertEquals("/post/x", LanguageHelper.localizedPath("/post/x", "en-US", "en-US"));
        assertEquals("/en-US/", LanguageHelper.localizedPath("/", "en-US", "zh-cn"));
        assertEquals("/", LanguageHelper.stripLanguagePrefix("/zh-cn"));
        assertEquals("/post/x", LanguageHelper.stripLanguagePrefix("/zh-cn/post/x"));
        assertEquals("/post/x", LanguageHelper.stripLanguagePrefix("/post/x"));
    }

    @Test
    void shouldReportContentLanguageWithFallback() {
        Map<String, String> localized = new LinkedHashMap<>();
        localized.put("zh-CN", "中文正文");
        assertEquals("zh-cn", LanguageHelper.resolveLocalizedLanguage(localized, "en-US"));
        localized.put("en-US", "English body");
        assertEquals("en-US", LanguageHelper.resolveLocalizedLanguage(localized, "en-US"));
    }

    @Test
    void shouldFallbackToFirstNonBlankWhenDefaultMissing() {
        Map<String, String> localized = new LinkedHashMap<>();
        localized.put("en-US", "English Title");
        localized.put("zh-CN", "");

        assertEquals("English Title", LanguageHelper.resolveLocalizedValue(localized, "ja-JP"));
    }
}
