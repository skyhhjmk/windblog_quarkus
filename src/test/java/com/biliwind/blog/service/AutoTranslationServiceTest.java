package com.biliwind.blog.service;

import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.AutoTranslationRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.AutoTranslationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTranslationServiceTest {

    @Test
    void tracksStateTransitionsInMemory() {
        AutoTranslationService service = new AutoTranslationService();
        service.objectMapper = new ObjectMapper();

        assertFalse(service.isEnabled());
        assertEquals("en-US", service.getTargetLanguage());

        // Update target language and enabled
        AutoTranslationStatus status1 = service.updateStatus(new AutoTranslationRequest(true, "zh-cn"));
        assertTrue(status1.enabled());
        assertEquals("zh-cn", status1.targetLanguage());
        assertTrue(service.isEnabled());
        assertEquals("zh-cn", service.getTargetLanguage());

        AutoTranslationStatus status2 = service.updateStatus(new AutoTranslationRequest(false, "en-US"));
        assertFalse(status2.enabled());
        assertEquals("en-US", status2.targetLanguage());
        assertFalse(service.isEnabled());
        assertEquals("en-US", service.getTargetLanguage());
    }
}
