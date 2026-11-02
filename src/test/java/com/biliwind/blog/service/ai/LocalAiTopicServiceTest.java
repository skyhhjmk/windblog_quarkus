package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LocalAiTopicServiceTest {

    @Inject
    LocalAiTopicService service;

    @Inject
    ObjectMapper objectMapper;

    @Test
    @Transactional
    void importsListsAndReviewsTopicsWithoutCodex() throws Exception {
        String title = "本地话题 " + UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of("topics", List.of(Map.of(
                "title", title,
                "rationale", "验证本地话题池不依赖 Codex Creator。",
                "seedId", 7,
                "keywords", List.of("quarkus", "topic"),
                "recommendation", "WRITE",
                "provider", "OPENAI",
                "model", "test-model",
                "language", "zh-CN"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> imported = (Map<String, Object>) service.dispatch(
                "topics.import", payload, "tester", "trace");
        assertEquals(1, imported.get("created"));

        Long topicId = findTopicId(title);
        JsonNode read = objectMapper.valueToTree(service.dispatch(
                "topics.read", objectMapper.valueToTree(Map.of("id", topicId)), "tester", "trace"));
        assertEquals("SUGGESTED", read.path("status").asText());
        assertEquals(7, read.path("source").path("seeds").path(0).path("id").asLong());
        assertEquals("quarkus", read.path("source").path("keywords").path(0).asText());

        JsonNode reviewed = objectMapper.valueToTree(service.dispatch(
                "topics.review", objectMapper.valueToTree(Map.of("id", topicId, "decision", "APPROVE")),
                "tester", "trace"));
        assertEquals("APPROVED", reviewed.path("status").asText());

        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) service.dispatch(
                "topics.list", objectMapper.valueToTree(Map.of("status", "APPROVED", "page", 1, "pageSize", 100)),
                "tester", "trace");
        assertTrue(((List<?>) list.get("items")).stream()
                .anyMatch(item -> title.equals(((Map<?, ?>) item).get("title"))));

        JsonNode assigned = objectMapper.valueToTree(service.dispatch(
                "topics.list",
                objectMapper.valueToTree(Map.of("status", "ASSIGNED", "page", 1, "pageSize", 20)),
                "tester", "trace"));
        assertTrue(assigned.path("items").isArray());
    }

    @Test
    @Transactional
    void managesSeedsAndSettingsLocally() {
        String name = "seed-" + UUID.randomUUID();
        JsonNode seed = objectMapper.valueToTree(service.dispatch("seeds.upsert",
                objectMapper.valueToTree(Map.of(
                        "name", name, "query", "quarkus ai topic", "language", "zh-CN")),
                "tester", "trace"));
        long seedId = seed.path("id").asLong();
        assertTrue(seedId > 0);

        JsonNode seeds = objectMapper.valueToTree(service.dispatch("seeds.list",
                objectMapper.createObjectNode(), "tester", "trace"));
        assertTrue(seeds.path("items").toString().contains(name));

        JsonNode settings = objectMapper.valueToTree(service.dispatch("settings.update",
                objectMapper.valueToTree(Map.of(
                        "enabled", true, "intervalMinutes", 720, "maxTopicsPerRun", 12)),
                "tester", "trace"));
        assertEquals(720, settings.path("intervalMinutes").asInt());
        assertEquals("LOCAL", settings.path("storage").asText());

        service.dispatch("seeds.delete", objectMapper.valueToTree(Map.of("id", seedId)),
                "tester", "trace");
        JsonNode after = objectMapper.valueToTree(service.dispatch("seeds.list",
                objectMapper.createObjectNode(), "tester", "trace"));
        assertTrue(!after.path("items").toString().contains(name));
    }

    private Long findTopicId(String title) {
        JsonNode list = objectMapper.valueToTree(service.dispatch("topics.list",
                objectMapper.valueToTree(Map.of("page", 1, "pageSize", 100)),
                "tester", "trace"));
        for (JsonNode item : list.path("items")) {
            if (title.equals(item.path("title").asText())) {
                return item.path("id").asLong();
            }
        }
        throw new AssertionError("imported topic not found");
    }
}
