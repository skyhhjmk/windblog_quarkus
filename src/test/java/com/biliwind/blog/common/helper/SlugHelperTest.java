package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlugHelperTest {

    @Test
    void shouldTransliterateChineseTitlesToUrlSafePinyin() {
        String slug = SlugHelper.slugify("如何用 Quarkus 构建原生应用");

        assertTrue(slug.matches("[a-z0-9-]+"), slug);
        assertTrue(slug.contains("quarkus"), slug);
        assertEquals("ru-he-yong-quarkus-gou-jian-yuan-sheng-ying-yong", slug);
    }

    @Test
    void shouldDropUrlUnfriendlyCharacters() {
        assertEquals("hello-world", SlugHelper.slugify("  Hello, World!  "));
        assertEquals("abc-def", SlugHelper.slugify("ABC_def"));
        assertEquals("", SlugHelper.slugify("!!!"));
        assertEquals("", SlugHelper.slugify((String) null));
    }

    @Test
    void shouldGenerateFromLocalizedTitleMap() {
        assertEquals("kuai-su-ru-men",
                SlugHelper.slugify(Map.of("zh-cn", "快速入门", "en-US", "Quick Start"), "zh-cn"));
        assertEquals("quick-start",
                SlugHelper.slugify(Map.of("zh-cn", "快速入门", "en-US", "Quick Start"), "en-US"));
    }

    @Test
    void shouldCapSlugAtDatabaseLength() {
        String slug = SlugHelper.slugify(("very long title ").repeat(40));

        assertTrue(slug.length() <= SlugHelper.MAX_LENGTH, slug);
        assertFalse(slug.endsWith("-"), slug);
    }
}
