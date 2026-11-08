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
        long initialLike = initial.get("like");

        Map<String, Long> afterReact = service.updateReaction(slug, "like", "react");
        assertEquals(initialLike + 1, afterReact.get("like"));

        Map<String, Long> afterUnreact = service.updateReaction(slug, "like", "unreact");
        assertEquals(initialLike, afterUnreact.get("like"));
    }

    @Test
    void shouldRejectInvalidReactionType() {
        ReactionService service = new ReactionService();
        assertThrows(IllegalArgumentException.class, () ->
                service.updateReaction("slug", "invalid_type", "react"));
    }
}
