package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionValidationServiceTest {

    private final RegionValidationService service = new RegionValidationService();

    @Test
    void shouldNormalizeNullOrEmptyToNull() {
        assertNull(service.validateAndFilterRegions(null));
        assertNull(service.validateAndFilterRegions(List.of()));
        assertNull(service.validateAndFilterRegions(List.of("   ", "")));
    }

    @Test
    void shouldNormalizeGlobalOrSelectionContainingGlobalToNull() {
        assertNull(service.validateAndFilterRegions(List.of("global")));
        assertNull(service.validateAndFilterRegions(List.of("GLOBAL")));
        assertNull(service.validateAndFilterRegions(List.of("global", "cn")));
        assertNull(service.validateAndFilterRegions(List.of("cn", "global")));
    }

    @Test
    void shouldKeepSpecificValidRegions() {
        assertEquals(List.of("cn"), service.validateAndFilterRegions(List.of("cn")));
        assertEquals(List.of("cn"), service.validateAndFilterRegions(List.of("china")));
        assertEquals(List.of("cn", "us"), service.validateAndFilterRegions(List.of("cn", "us")));
        assertEquals(List.of("jp"), service.validateAndFilterRegions(List.of("jp", "invalid_unknown_code")));
    }

    @Test
    void shouldValidateSingleRegionCode() {
        assertTrue(service.isValidRegion("cn"));
        assertTrue(service.isValidRegion("china"));
        assertTrue(service.isValidRegion("global"));
        assertTrue(service.isValidRegion("GLOBAL"));
        assertFalse(service.isValidRegion(null));
        assertFalse(service.isValidRegion(""));
        assertFalse(service.isValidRegion("unknown_zone"));
    }
}
