package com.biliwind.blog.service;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import jakarta.ws.rs.core.EntityTag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageRenderModelTest {

    @Test
    void fingerprintDoesNotDependOnInputOrder() {
        PageRenderModel first = new PageRenderModel()
                .put("language", "zh-cn")
                .put("postId", 1L)
                .put("title", "标题");
        PageRenderModel second = new PageRenderModel()
                .put("title", "标题")
                .put("postId", 1L)
                .put("language", "zh-cn");

        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.validator(), second.validator());
    }

    @Test
    void everyRenderInputChangesTheValidator() {
        PageRenderModel base = new PageRenderModel()
                .put("language", "zh-cn")
                .put("postId", 1L)
                .put("title", "标题")
                .put("tags", List.of("a", "b"))
                .put("metadata", Map.of("og:title", "标题", "og:type", "article"));
        String baseFingerprint = base.fingerprint();

        assertNotEquals(baseFingerprint, new PageRenderModel().put("language", "en-US")
                .put("postId", 1L).put("title", "标题")
                .put("tags", List.of("a", "b"))
                .put("metadata", Map.of("og:title", "标题", "og:type", "article")).fingerprint());
        assertNotEquals(baseFingerprint, new PageRenderModel().put("language", "zh-cn")
                .put("postId", 2L).put("title", "标题")
                .put("tags", List.of("a", "b"))
                .put("metadata", Map.of("og:title", "标题", "og:type", "article")).fingerprint());
        assertNotEquals(baseFingerprint, new PageRenderModel().put("language", "zh-cn")
                .put("postId", 1L).put("title", "另一个标题")
                .put("tags", List.of("a", "b"))
                .put("metadata", Map.of("og:title", "标题", "og:type", "article")).fingerprint());
        assertNotEquals(baseFingerprint, new PageRenderModel().put("language", "zh-cn")
                .put("postId", 1L).put("title", "标题")
                .put("tags", List.of("a", "b", "c"))
                .put("metadata", Map.of("og:title", "标题", "og:type", "article")).fingerprint());
        assertNotEquals(baseFingerprint, new PageRenderModel().put("language", "zh-cn")
                .put("postId", 1L).put("title", "标题")
                .put("tags", List.of("a", "b"))
                .put("metadata", Map.of("og:title", "新标题", "og:type", "article")).fingerprint());
    }

    @Test
    void mapOrderDoesNotChangeTheFingerprint() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("a", "1");
        one.put("b", "2");
        Map<String, String> two = new LinkedHashMap<>();
        two.put("b", "2");
        two.put("a", "1");

        assertEquals(new PageRenderModel().put("og", one).fingerprint(),
                new PageRenderModel().put("og", two).fingerprint());
    }

    @Test
    void validatorIsWeakAndSafeForIfNoneMatch() {
        EntityTag etag = new PageRenderModel().put("robots", "index,follow").validator();

        assertTrue(etag.isWeak());
        assertFalse(etag.getValue().contains(","));
        assertFalse(etag.getValue().isBlank());
    }

    @Test
    void validatorSealsTheModelSoTheRenderedDataCannotDrift() {
        PageRenderModel model = new PageRenderModel().put("title", "标题");
        model.validator();

        assertThrows(IllegalStateException.class, () -> model.put("late", "input"));
    }

    @Test
    void derivedValuesRenderButDoNotChangeTheValidator() {
        Engine engine = Engine.builder().addDefaults().build();
        Template template = engine.parse("{title}|{body}");

        PageRenderModel first = new PageRenderModel()
                .put("title", "标题")
                .derived("body", () -> "rendered-a");
        PageRenderModel second = new PageRenderModel()
                .put("title", "标题")
                .derived("body", () -> "rendered-b");

        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals("标题|rendered-a", first.render(template).render());
        assertEquals("标题|rendered-b", second.render(template).render());
        assertEquals(java.util.Set.of("body"), first.derivedKeys());
    }

    @Test
    void derivedValuesCannotBeAddedAfterSealing() {
        PageRenderModel model = new PageRenderModel().put("title", "标题");
        model.validator();

        assertThrows(IllegalStateException.class, () -> model.derived("body", () -> "late"));
    }

    @Test
    void renderFeedsExactlyTheHashedInputsToTheTemplate() {
        Engine engine = Engine.builder().addDefaults().build();
        Template template = engine.parse("{title}|{postId}");
        PageRenderModel model = new PageRenderModel()
                .put("title", "标题")
                .put("postId", 7L);

        assertEquals("标题|7", model.render(template).render());
        assertThrows(IllegalStateException.class, () -> model.put("late", "input"));
    }
}
