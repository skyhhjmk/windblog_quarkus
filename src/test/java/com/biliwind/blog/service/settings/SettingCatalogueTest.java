package com.biliwind.blog.service.settings;

import com.biliwind.blog.model.SettingLevel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingCatalogueTest {

    @Test
    void catalogueIsValidAndHasOneDefinitionPerKey() {
        SettingCatalogue.validate();

        Set<SettingKey> seen = new HashSet<>();
        for (SettingDefinition definition : SettingCatalogue.definitions()) {
            assertTrue(seen.add(definition.key()), "duplicate declaration: " + definition.key());
            assertNotNull(definition.defaultValue());
            assertEquals(definition.key(), SettingKey.fromKey(definition.key().key()));
        }
    }

    @Test
    void migrationOnlyKeysAreDeclaredInTheCatalogue() {
        for (String key : new String[]{
                "seo", "legal_terms", "legal_privacy", "gamification",
                "daily_check_in_rewards", "google_adsense"}) {
            assertNotNull(SettingCatalogue.findByKey(key), "missing catalogue declaration: " + key);
        }
    }

    @Test
    void criticalAndSecretFlagsComeFromTheDeclaration() {
        assertTrue(SettingCatalogue.find(SettingKey.ELASTICSEARCH).requiresVerification());
        assertTrue(SettingCatalogue.find(SettingKey.SECURITY_NETWORK).requiresVerification());
        assertTrue(SettingCatalogue.find(SettingKey.COMMENT_SECURITY).requiresVerification());
        assertFalse(SettingCatalogue.find(SettingKey.SITE_INFO).requiresVerification());
        assertTrue(SettingCatalogue.find(SettingKey.ELASTICSEARCH).secretFields().contains("password"));
        assertEquals(SettingLevel.CRITICAL, SettingCatalogue.find(SettingKey.ELASTICSEARCH).level());
    }

    @Test
    void seededDefaultShapesArePreserved() {
        assertEquals("[]", SettingCatalogue.find(SettingKey.GOOGLE_ADSENSE)
                .defaultValue().path("blocks").asText());
        assertTrue(SettingCatalogue.find(SettingKey.SITE_INFO)
                .defaultValue().path("keywords").isArray());
        assertFalse(SettingCatalogue.find(SettingKey.SEO)
                .defaultValue().path("internal_link_graph_enabled").asBoolean(true));
        assertEquals("#1A1A1A", SettingCatalogue.find(SettingKey.APPEARANCE)
                .defaultValue().path("theme_color").asText());
    }

    @Test
    void regionalKindsMatchTheActualStorageShapes() {
        assertEquals(RegionalKind.LANGUAGE, SettingCatalogue.find(SettingKey.SITE_LANGUAGE).regionalKind());
        assertEquals(RegionalKind.FAVICON, SettingCatalogue.find(SettingKey.APPEARANCE).regionalKind());
        assertEquals(RegionalKind.OBJECT_REGIONS, SettingCatalogue.find(SettingKey.ANALYTICS_TRACKING).regionalKind());
        assertEquals(RegionalKind.NONE, SettingCatalogue.find(SettingKey.SEO).regionalKind());
    }
}
