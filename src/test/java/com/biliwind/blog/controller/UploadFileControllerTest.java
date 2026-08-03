package com.biliwind.blog.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UploadFileControllerTest {

    @Test
    void shouldResolveKnownGeneratedVariantBasenames() {
        assertEquals("media-123", UploadFileController.extractGeneratedVariantBase(
                "media-123_placeholder.jpg"));
        assertEquals("media-123", UploadFileController.extractGeneratedVariantBase(
                "media-123_cover.jpg"));
        assertEquals("media-123", UploadFileController.extractGeneratedVariantBase(
                "media-123_p.jpg"));
        assertEquals("media-123", UploadFileController.extractGeneratedVariantBase(
                "media-123.webp"));
    }

    @Test
    void shouldNotTreatArbitraryUploadNamesAsGeneratedVariants() {
        assertNull(UploadFileController.extractGeneratedVariantBase("media-123.txt"));
        assertNull(UploadFileController.extractGeneratedVariantBase("_cover.jpg"));
    }
}
