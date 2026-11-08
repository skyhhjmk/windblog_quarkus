package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactionServiceTest {

    @Test
    void shouldSeedDefaultCountsAndIncrement() {
        ReactionService service = new ReactionService();
        String slug = "unit-test-post-pow";

        Map<String, Long> initial = service.getReactions(slug);
        assertNotNull(initial);
        assertTrue(initial.containsKey("like"));
        assertTrue(initial.containsKey("heart"));
        assertTrue(initial.containsKey("insightful"));
        assertTrue(initial.containsKey("rocket"));
        assertTrue(initial.containsKey("celebrate"));

        long initialLike = initial.get("like");
        assertTrue(initialLike >= 16 && initialLike <= 38, "Likes should be in reasonable initial range [16, 38]");
        assertTrue(initial.get("heart") >= 11 && initial.get("heart") <= 28, "Hearts should be in reasonable initial range [11, 28]");
        assertTrue(initial.get("insightful") >= 6 && initial.get("insightful") <= 19);
        assertTrue(initial.get("rocket") >= 3 && initial.get("rocket") <= 14);
        assertTrue(initial.get("celebrate") >= 2 && initial.get("celebrate") <= 11);

        Map<String, Long> afterReact = service.updateReaction(slug, "like", "react");
        assertEquals(initialLike + 1, afterReact.get("like"));

        Map<String, Long> afterUnreact = service.updateReaction(slug, "like", "unreact");
        assertEquals(initialLike, afterUnreact.get("like"));
    }

    @Test
    void shouldGenerateDeterministicReasonableFakeReactionsForDifferentSlugs() {
        ReactionService service = new ReactionService();
        String[] sampleSlugs = {"welcome-to-windblog", "quarkus-performance-tuning", "security-guide-2026"};

        for (String s : sampleSlugs) {
            Map<String, Long> reactions = service.getReactions(s);
            long total = reactions.values().stream().mapToLong(Long::longValue).sum();
            assertTrue(total >= 38, "Total reactions should look warm and healthy for: " + s);

            // Verify determinism
            Map<String, Long> repeated = service.getReactions(s);
            assertEquals(reactions, repeated);
        }
    }

    @Test
    void shouldRejectInvalidReactionType() {
        ReactionService service = new ReactionService();
        assertThrows(IllegalArgumentException.class, () ->
                service.updateReaction("slug", "invalid_type", "react"));
    }

    @Test
    void shouldCorrectlyHandlePostReactionEntity() {
        com.biliwind.blog.model.PostReaction reaction = new com.biliwind.blog.model.PostReaction();
        reaction.postSlug = "demo-article";
        reaction.likeCount = 20;
        reaction.heartCount = 15;
        reaction.rocketCount = 5;
        reaction.celebrateCount = 3;
        reaction.insightfulCount = 8;

        assertEquals(20, reaction.getCount("like"));
        assertEquals(15, reaction.getCount("heart"));

        reaction.applyDelta("like", 1);
        assertEquals(21, reaction.getCount("like"));

        reaction.applyDelta("like", -5);
        assertEquals(16, reaction.getCount("like"));

        reaction.applyDelta("rocket", -10);
        assertEquals(0, reaction.getCount("rocket"));

        Map<String, Long> map = reaction.toMap();
        assertEquals(16, map.get("like"));
        assertEquals(15, map.get("heart"));
        assertEquals(0, map.get("rocket"));
        assertEquals(3, map.get("celebrate"));
        assertEquals(8, map.get("insightful"));
    }
}
