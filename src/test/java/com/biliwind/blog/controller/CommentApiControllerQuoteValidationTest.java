package com.biliwind.blog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentApiControllerQuoteValidationTest {

    private CommentApiController controller;

    @BeforeEach
    void setUp() {
        controller = new CommentApiController();
        controller.objectMapper = new ObjectMapper();
    }

    @Test
    void acceptsValidQuoteTypesAndRejectsUnknownOnes() {
        assertNull(controller.normalizeQuoteType(null));
        assertNull(controller.normalizeQuoteType("   "));
        assertEquals("QUOTE", controller.normalizeQuoteType(" quote "));
        assertEquals("CORRECTION", controller.normalizeQuoteType("correction"));
        assertThrows(BadRequestException.class, () -> controller.normalizeQuoteType("INJECTED"));
    }

    @Test
    void validatesQuoteTextPresenceAndLength() {
        assertEquals("被引用的句子", controller.normalizeQuoteText("  被引用的句子  "));
        assertThrows(BadRequestException.class, () -> controller.normalizeQuoteText(null));
        assertThrows(BadRequestException.class, () -> controller.normalizeQuoteText("   "));
        assertThrows(BadRequestException.class, () -> controller.normalizeQuoteText("a".repeat(1001)));
    }

    @Test
    void validatesAnchorDataObjectAndSize() {
        Object parsed = controller.parseAnchorData("{\"exact\":\"hello\",\"paragraphIndex\":2}");
        JsonNode node = assertInstanceOf(JsonNode.class, parsed);
        assertEquals("hello", node.get("exact").asText());

        Object fallback = controller.parseAnchorData("[\"not-an-object\"]");
        assertTrue(assertInstanceOf(Map.class, fallback).isEmpty());

        Object malformed = controller.parseAnchorData("{bad json");
        assertTrue(assertInstanceOf(Map.class, malformed).isEmpty());

        assertThrows(BadRequestException.class, () -> controller.parseAnchorData("x".repeat(4097)));
    }
}
