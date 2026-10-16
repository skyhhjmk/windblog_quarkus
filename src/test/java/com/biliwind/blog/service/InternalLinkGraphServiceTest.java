package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InternalLinkGraphServiceTest {
    private InternalLinkGraphService.Source source(long id, String slug, String... links) {
        return new InternalLinkGraphService.Source(id, slug, slug, "/post/" + slug,
                new LinkedHashSet<>(List.of(links)), false);
    }

    @Test void publicDepthDoesNotExposeGrandchildrenOrUnavailableArticles() {
        Map<String, InternalLinkGraphService.Source> articles = Map.of(
                "a", source(1, "a", "b", "private", "missing"), "b", source(2, "b", "c"), "c", source(3, "c"));
        var graph = InternalLinkGraphService.traverse("a", 1, 80, articles::get);
        assertEquals(List.of("a", "b"), graph.nodes().stream().map(InternalLinkGraphService.Node::slug).toList());
        assertEquals(1, graph.edges().size());
        assertFalse(graph.truncated());
    }

    @Test void recursionRetainsCyclesWithoutExpandingThemForever() {
        Map<String, InternalLinkGraphService.Source> articles = Map.of(
                "a", source(1, "a", "b", "a"), "b", source(2, "b", "c"), "c", source(3, "c", "a"));
        var graph = InternalLinkGraphService.traverse("a", 8, 150, articles::get);
        assertEquals(3, graph.nodes().size());
        assertEquals(3, graph.edges().size());
        assertEquals(List.of(0, 1, 2), graph.nodes().stream().map(InternalLinkGraphService.Node::depth).toList());
    }

    @Test void nodeBudgetStopsFurtherDestinationLoads() {
        List<String> loaded = new ArrayList<>();
        var graph = InternalLinkGraphService.traverse("a", 8, 2, slug -> {
            loaded.add(slug);
            return slug.equals("a") ? source(1, "a", "b", "c") : source(2, slug);
        });
        assertEquals(2, graph.nodes().size());
        assertTrue(graph.truncated());
        assertEquals(List.of("a", "b"), loaded);
    }

    @Test void extractorHandlesAliasesLanguagesAndRejectsExternalLookalikes() {
        String html = "<a href='/post/old?ref=x#part'>old</a><a href='/en-US/post/translated'>en</a>"
                + "<a href='/amp/post/light'>amp</a><a href='https://blog.test/post/old'>duplicate</a>"
                + "<a href='https://blog.test.evil.test/post/evil'>evil</a><a href='mailto:hello@blog.test'>mail</a>"
                + "<a href='https://user:pass@blog.test/post/private'>credentials</a><a href='/post/cycle'>cycle</a>";
        var targets = InternalLinkGraphService.extractTargets(html, "https://blog.test/post/root", Set.of("blog.test"),
                Map.of("/post/old", "/post/new", "/post/cycle", "/post/cycle"));
        assertEquals(Set.of("new", "translated", "light"), targets);
    }
}
