package com.biliwind.blog.service;

import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class PageRendererTest {

    @Inject
    PageRenderer renderer;

    @Inject
    Engine engine;

    @Test
    void everyPageViewResolvesItsDocumentAndFragment() {
        for (PageView view : PageView.values()) {
            assertNotNull(engine.getTemplate(view.documentId()), view.documentId());
            assertNotNull(engine.getTemplate(view.fragmentId()), view.fragmentId());
            assertNotNull(renderer.template(view, false), view.documentId());
            assertNotNull(renderer.template(view, true), view.fragmentId());
        }
    }
}
