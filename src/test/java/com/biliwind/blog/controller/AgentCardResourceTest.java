package com.biliwind.blog.controller;

import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.service.PublicUrlService;
import com.biliwind.blog.service.search.PostSearchQuery;
import com.biliwind.blog.service.search.PostSearchResult;
import com.biliwind.blog.service.search.PostSearchService;
import com.biliwind.blog.service.search.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentCardResourceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private AgentCardResource card() {
        AgentCardResource card = new AgentCardResource();
        card.publicUrlService = new PublicUrlService() {
            @Override
            public String getBaseUrl() {
                return "https://www.geekcorelab.com";
            }
        };
        return card;
    }

    private PostSearchResult twoHits() {
        return new PostSearchResult(List.of(
                new SearchHit("post", "Post", "Quarkus Guide", "A short summary.", "",
                        "/post/quarkus-guide", "quarkus-guide", null, "2026-01-01", "",
                        "[Read More]", null, null, 12, List.of(), List.of()),
                new SearchHit("post", "Post", "Java Basics", "   ", "",
                        "https://blog.example.com/post/java-basics", "java-basics", null,
                        "2026-01-02", "", "[Read More]", null, null, 3, List.of(), List.of())
        ), 2, false, true, true);
    }

    private PostSearchService fakeSearch(PostSearchResult result) {
        return new PostSearchService() {
            @Override
            public PostSearchResult search(PostSearchQuery query) {
                return result;
            }
        };
    }

    private A2aResource agent() {
        return agent(twoHits());
    }

    private A2aResource agent(PostSearchResult result) {
        A2aResource agent = new A2aResource();
        agent.agentCard = card();
        agent.languageContext = new LanguageContext();
        agent.postSearchService = fakeSearch(result);
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
        assertTrue(text.contains("https://www.geekcorelab.com/api/public/docs"));
    }

    @Test
    void returnsPublicSearchHitsWithAbsoluteUrlsAndSummaries() throws Exception {
        JsonNode request = mapper.readTree("""
                {"message":{"messageId":"test","role":"ROLE_USER","parts":[{"text":"Quarkus"}]}}
                """);
        Response response = agent().send(request, "1.0");
        assertEquals(200, response.getStatus());
        JsonNode reply = mapper.valueToTree(response.getEntity());
        String text = reply.at("/message/parts/0/text").asText();
        assertTrue(text.contains("Found 2 public articles for \"Quarkus\":"), text);
        assertTrue(text.contains("1. Quarkus Guide - https://www.geekcorelab.com/post/quarkus-guide"), text);
        assertTrue(text.contains("\n  A short summary."), text);
        assertTrue(text.contains("2. Java Basics - https://blog.example.com/post/java-basics"), text);
        assertFalse(text.contains("   \n"), "blank summaries must be omitted");
        assertFalse(reply.at("/message/contextId").asText().isBlank());
    }

    @Test
    void reportsNoMatchesWhenSearchIsEmpty() throws Exception {
        JsonNode request = mapper.readTree("""
                {"message":{"messageId":"test","role":"ROLE_USER","parts":[{"text":"nothing"}]}}
                """);
        Response response = agent(new PostSearchResult(List.of(), 0, false, true, true))
                .send(request, "1.0");
        assertEquals(200, response.getStatus());
        String text = mapper.valueToTree(response.getEntity()).at("/message/parts/0/text").asText();
        assertTrue(text.startsWith("No public articles matched \"nothing\"."), text);
        assertTrue(text.contains("Search public articles: https://www.geekcorelab.com/search?q=nothing"));
    }

    @Test
    void fallsBackToLinksWhenSearchFails() throws Exception {
        A2aResource agent = agent(twoHits());
        agent.postSearchService = new PostSearchService() {
            @Override
            public PostSearchResult search(PostSearchQuery query) {
                throw new IllegalStateException("search unavailable");
            }
        };
        JsonNode request = mapper.readTree("""
                {"message":{"messageId":"test","role":"ROLE_USER","parts":[{"text":"Java"}]}}
                """);
        Response response = agent.send(request, "1.0");
        assertEquals(200, response.getStatus());
        String text = mapper.valueToTree(response.getEntity()).at("/message/parts/0/text").asText();
        assertTrue(text.startsWith("Search public articles: "), text);
        assertFalse(text.contains("Found "), text);
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
