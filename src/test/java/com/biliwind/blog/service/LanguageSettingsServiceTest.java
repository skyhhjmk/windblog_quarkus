package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.model.BlogRegion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LanguageSettingsServiceTest {

    @Inject
    LanguageSettingsService languageSettingsService;

    @Test
    void globalAndChinaDefaultsDiffer() {
        assertEquals(LanguageConstant.LANG_EN_US,
                languageSettingsService.defaultLanguage(BlogRegion.GLOBAL));
        assertEquals(LanguageConstant.LANG_ZH_CN,
                languageSettingsService.defaultLanguage(BlogRegion.CN));
    }

    @Test
    void unconfiguredRegionsInheritGlobal() {
        assertEquals(LanguageConstant.LANG_EN_US,
                languageSettingsService.defaultLanguage(BlogRegion.US));
        assertEquals(BlogRegion.values().length, languageSettingsService.listSettings().size());
    }

    @Test
    void updatesRegionLanguageAndRefreshesTheCache() {
        String original = languageSettingsService.defaultLanguage(BlogRegion.CN);
        try {
            languageSettingsService.updateDefaultLanguage(BlogRegion.CN, "en-US", 1L, "test");
            assertEquals(LanguageConstant.LANG_EN_US,
                    languageSettingsService.defaultLanguage(BlogRegion.CN));
        } finally {
            languageSettingsService.updateDefaultLanguage(BlogRegion.CN, original, 1L, "test restore");
        }
        assertEquals(original, languageSettingsService.defaultLanguage(BlogRegion.CN));
    }

    @Test
    void rejectsUnsupportedLanguage() {
        assertThrows(com.biliwind.blog.common.exception.BadRequestException.class,
                () -> languageSettingsService.updateDefaultLanguage(BlogRegion.CN, "fr-FR", 1L, "test"));
    }

    @Test
    void allSettingsUseSupportedLanguages() {
        languageSettingsService.listSettings().forEach(setting ->
                assertTrue(LanguageConstant.SUPPORT_LANGS.contains(setting.defaultLanguage())));
    }
}
