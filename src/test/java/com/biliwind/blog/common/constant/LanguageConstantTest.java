package com.biliwind.blog.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanguageConstantTest {

    @Test
    void shouldHaveCorrectDefaultLanguage() {
        assertEquals("zh-cn", LanguageConstant.DEFAULT_LANG);
    }

    @Test
    void shouldHaveCorrectZhCnLanguage() {
        assertEquals("zh-cn", LanguageConstant.LANG_ZH_CN);
    }

    @Test
    void shouldHaveCorrectEnUsLanguage() {
        assertEquals("en-US", LanguageConstant.LANG_EN_US);
    }

    @Test
    void shouldSupportTwoLanguages() {
        assertEquals(2, LanguageConstant.SUPPORT_LANGS.size());
    }

    @Test
    void shouldContainZhCnInSupportedLanguages() {
        assertTrue(LanguageConstant.SUPPORT_LANGS.contains("zh-cn"));
    }

    @Test
    void shouldContainEnUsInSupportedLanguages() {
        assertTrue(LanguageConstant.SUPPORT_LANGS.contains("en-US"));
    }

    @Test
    void shouldNotContainUnsupportedLanguage() {
        assertFalse(LanguageConstant.SUPPORT_LANGS.contains("fr-FR"));
    }

    @Test
    void shouldHaveCorrectCookieHeaderName() {
        assertEquals("Cookie", LanguageConstant.HEADER_COOKIE);
    }

    @Test
    void shouldHaveCorrectLangCookieKey() {
        assertEquals("user_lang", LanguageConstant.COOKIE_LANG_KEY);
    }

    @Test
    void shouldHaveCorrectRequestLangHeader() {
        assertEquals("X-Request-Lang", LanguageConstant.HEADER_REQUEST_LANG);
    }

    @Test
    void shouldHaveCorrectAcceptLangHeader() {
        assertEquals("Accept-Language", LanguageConstant.HEADER_ACCEPT_LANG);
    }

    @Test
    void shouldDefaultLangBeInSupportedLangs() {
        assertTrue(LanguageConstant.SUPPORT_LANGS.contains(LanguageConstant.DEFAULT_LANG));
    }
}