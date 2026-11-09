package com.biliwind.blog.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArticleModeTest {

    @Test
    void articleModeEnumValuesAndCodes() {
        assertEquals((short) 0, ArticleMode.STANDARD.code());
        assertEquals("standard", ArticleMode.STANDARD.value());

        assertEquals((short) 1, ArticleMode.TUTORIAL.code());
        assertEquals("tutorial", ArticleMode.TUTORIAL.value());

        assertEquals((short) 2, ArticleMode.RESOURCE.code());
        assertEquals("resource", ArticleMode.RESOURCE.value());

        assertEquals(ArticleMode.STANDARD, ArticleMode.fromCode((short) 0));
        assertEquals(ArticleMode.TUTORIAL, ArticleMode.fromCode((short) 1));
        assertEquals(ArticleMode.RESOURCE, ArticleMode.fromCode((short) 2));
        assertEquals(ArticleMode.STANDARD, ArticleMode.fromCode(null));
        assertEquals(ArticleMode.STANDARD, ArticleMode.fromCode((short) 99));

        assertEquals(ArticleMode.STANDARD, ArticleMode.fromValue("standard"));
        assertEquals(ArticleMode.TUTORIAL, ArticleMode.fromValue("tutorial"));
        assertEquals(ArticleMode.RESOURCE, ArticleMode.fromValue("resource"));
        assertEquals(ArticleMode.STANDARD, ArticleMode.fromValue("unknown"));
    }

    @Test
    void articleModeConverterWorksProperly() {
        ArticleModeConverter converter = new ArticleModeConverter();
        assertEquals((short) 0, converter.convertToDatabaseColumn(ArticleMode.STANDARD));
        assertEquals((short) 1, converter.convertToDatabaseColumn(ArticleMode.TUTORIAL));
        assertEquals((short) 2, converter.convertToDatabaseColumn(ArticleMode.RESOURCE));
        assertEquals((short) 0, converter.convertToDatabaseColumn(null));

        assertEquals(ArticleMode.STANDARD, converter.convertToEntityAttribute((short) 0));
        assertEquals(ArticleMode.TUTORIAL, converter.convertToEntityAttribute((short) 1));
        assertEquals(ArticleMode.RESOURCE, converter.convertToEntityAttribute((short) 2));
        assertEquals(ArticleMode.STANDARD, converter.convertToEntityAttribute(null));
    }

    @Test
    void tutorialAndResourceMetadataRecordsHandleNullsGracefully() {
        TutorialMeta tutorialMeta = new TutorialMeta(null, 25, null, null);
        assertNotNull(tutorialMeta.difficulty());
        assertNotNull(tutorialMeta.prerequisites());
        assertNotNull(tutorialMeta.targetAudience());
        assertEquals(25, tutorialMeta.estimatedMinutes());

        ResourceMeta resourceMeta = new ResourceMeta("1.0", "10MB", null, null, null, null, null, null);
        assertNotNull(resourceMeta.platforms());
        assertNotNull(resourceMeta.mirrors());

        ResourceMirror mirror = new ResourceMirror("Mirror 1", "https://example.com", "1234", null);
        assertFalse(mirror.requiresUnlock());
    }
}
