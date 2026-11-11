package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionQueryHelperTest {

    @Test
    void shouldBuildNamedJpqlWithGlobalFallback() {
        String jpql = RegionQueryHelper.visibilityRegionFilterJpql();
        assertTrue(jpql.contains(":regionPattern"));
        assertTrue(jpql.contains("\"global\""));
        assertTrue(jpql.contains("visibilityRegions is null"));
    }

    @Test
    void shouldBuildIndexedJpqlWithGlobalFallback() {
        String jpql = RegionQueryHelper.visibilityRegionFilterJpql(3);
        assertTrue(jpql.contains("?3"));
        assertTrue(jpql.contains("\"global\""));
        assertTrue(jpql.contains("visibilityRegions is null"));
    }

    @Test
    void shouldBuildRegionPattern() {
        assertEquals("\"cn\"", RegionQueryHelper.buildRegionPattern(BlogRegion.CN));
        assertEquals("\"global\"", RegionQueryHelper.buildRegionPattern(BlogRegion.GLOBAL));
        assertEquals("\"global\"", RegionQueryHelper.buildRegionPattern((BlogRegion) null));
        assertEquals("\"cn\"", RegionQueryHelper.buildRegionPattern("cn"));
        assertEquals("\"global\"", RegionQueryHelper.buildRegionPattern(""));
        assertEquals("\"global\"", RegionQueryHelper.buildRegionPattern((String) null));
    }
}
