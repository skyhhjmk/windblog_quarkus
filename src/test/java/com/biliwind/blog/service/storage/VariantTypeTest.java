package com.biliwind.blog.service.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VariantTypeTest {

    @Test
    public void testAllVariants() {
        VariantType[] variants = VariantType.values();
        assertEquals(5, variants.length);
    }

    @Test
    public void testOriginalVariant() {
        assertEquals("ORIGINAL", VariantType.ORIGINAL.name());
    }

    @Test
    public void testWebpVariant() {
        assertEquals("WEBP", VariantType.WEBP.name());
    }

    @Test
    public void testPlaceholderVariant() {
        assertEquals("PLACEHOLDER", VariantType.PLACEHOLDER.name());
    }

    @Test
    public void testCoverVariant() {
        assertEquals("COVER", VariantType.COVER.name());
    }

    @Test
    public void testRawVariant() {
        assertEquals("RAW", VariantType.RAW.name());
    }

    @Test
    public void testValueOf() {
        assertEquals(VariantType.ORIGINAL, VariantType.valueOf("ORIGINAL"));
        assertEquals(VariantType.WEBP, VariantType.valueOf("WEBP"));
        assertEquals(VariantType.PLACEHOLDER, VariantType.valueOf("PLACEHOLDER"));
        assertEquals(VariantType.COVER, VariantType.valueOf("COVER"));
        assertEquals(VariantType.RAW, VariantType.valueOf("RAW"));
    }
}
