package com.biliwind.blog.service;

import com.biliwind.blog.model.PostRenderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmpContentRendererTest {

    private final AmpContentRenderer renderer = new AmpContentRenderer();

    @Test
    void shouldConvertMarkdownImagesAndRemoveInteractiveElements() {
        renderer.publicContentSanitizer = new PublicContentSanitizer();

        AmpContentRenderer.RenderedContent result = renderer.render(
                PostRenderType.MARKDOWN,
                "# Title\n\n![cover](/uploads/cover.jpg)\n\n"
                        + "<script>alert(1)</script><button>buy</button><iframe src=\"https://evil.example\"></iframe>");

        assertTrue(result.html().contains("<amp-img"));
        assertTrue(result.html().contains("src=\"http://localhost:8080/uploads/cover.jpg\""));
        assertTrue(result.html().contains("width=\"800\""));
        assertTrue(result.html().contains("height=\"450\""));
        assertFalse(result.html().contains("<img"));
        assertFalse(result.html().contains("script"));
        assertFalse(result.html().contains("button"));
        assertFalse(result.html().contains("iframe"));
        assertTrue(result.imageCount() == 1);
        assertTrue(result.removedElementCount() >= 1);
    }

    @Test
    void shouldKeepConfiguredImageDimensionsWithinAmpLimits() {
        renderer.publicContentSanitizer = new PublicContentSanitizer();

        AmpContentRenderer.RenderedContent result = renderer.render(
                PostRenderType.HTML,
                "<img src=\"/uploads/cover.jpg\" width=\"1200\" height=\"630\" alt=\"cover\">");

        assertTrue(result.html().contains("width=\"1200\""));
        assertTrue(result.html().contains("height=\"630\""));
        assertTrue(result.html().contains("alt=\"cover\""));
    }
}
