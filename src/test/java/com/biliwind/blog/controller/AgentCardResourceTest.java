package com.biliwind.blog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCardResourceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private AgentCardResource card() {
        AgentCardResource card = new AgentCardResource();
        card.publicUrl = "https://www.geekcorelab.com///";
        return card;
    }

    private A2aResource agent() {
        A2aResource agent = new A2aResource();
        agent.agentCard = card();
        return agent;
    }

    @Test
    void publishesRequiredSchemaAndActualServiceUrl() {
        Response response = card().getAgentCard();
        assertEquals(200, response.getStatus());
        JsonNode card = mapper.valueToTree(response.getEntity());
        for (String field : new String[]{"name", "version", "description"}) {
            assertFalse(card.path(field).asText().isBlank());
        }
        JsonNode service = card.path("supportedInterfaces").get(0);
        assertEquals("https://www.geekcorelab.com/a2a", service.path("url").asText());
        assertEquals("HTTP+JSON", service.path("protocolBinding").asText());
        assertEquals("1.0", service.path("protocolVersion").asText());
        assertFalse(card.path("capabilities").path("streaming").asBoolean());
        assertEquals(2, card.path("skills").size());
        for (JsonNode skill : card.path("skills")) {
            for (String field : new String[]{"id", "name", "description"}) {
                assertFalse(skill.path(field).asText().isBlank());
            }
            assertFalse(skill.path("tags").isEmpty());
        }
    }

    @Test
    void returnsEncodedPublicLinksAndPreservesContext() throws Exception {
        JsonNode request = mapper.readTree("""
                {"message":{"messageId":"test","contextId":"context-1","role":"ROLE_USER",
                "parts":[{"text":"Java & Quarkus 中文"}]}}
                """);
        Response response = agent().send(request, "1.0");
        assertEquals(200, response.getStatus());
        JsonNode reply = mapper.valueToTree(response.getEntity());
        assertEquals("ROLE_AGENT", reply.at("/message/role").asText());
        assertEquals("context-1", reply.at("/message/contextId").asText());
        assertFalse(reply.at("/message/messageId").asText().isBlank());
        String text = reply.at("/message/parts/0/text").asText();
        assertTrue(text.contains("/search?q=Java+%26+Quarkus+%E4%B8%AD%E6%96%87"));
        assertTrue(text.contains("https://www.geekcorelab.com/api/public/openapi"));
    }

    @Test
    void acceptsTheDocumentedMaximumTextLength() throws Exception {
        JsonNode request = mapper.readTree("{\"message\":{\"messageId\":\"test\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\""
                + "x".repeat(4096) + "\"}]}}");
        assertEquals(200, agent().send(request, "1.0").getStatus());
    }

    @Test
    void rejectsUnsupportedVersionsAndInvalidMessages() throws Exception {
        assertEquals(400, agent().send(null, "1.0").getStatus());
        assertEquals(400, agent().send(null, "0.3").getStatus());
        assertEquals(400, agent().send(null, null).getStatus());
        for (String parts : new String[]{"[]", "[{\"text\":\" \"}]", "[{\"data\":{}}]",
                "[{\"text\":\"" + "x".repeat(4097) + "\"}]"}) {
            JsonNode request = mapper.readTree("{\"message\":{\"messageId\":\"test\",\"role\":\"ROLE_USER\",\"parts\":" + parts + "}}");
            assertEquals(400, agent().send(request, "1.0").getStatus());
        }
        JsonNode request = mapper.readTree("""
                {"message":{"messageId":"test","role":"ROLE_USER","parts":[{"text":"api"}]},
                "configuration":{"acceptedOutputModes":["image/png"]}}
                """);
        assertEquals(400, agent().send(request, "1.0").getStatus());
    }
}
