package com.biliwind.blog.filter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownNegotiationFilterTest {
    @Test
    void negotiatesExplicitMarkdownAndHonorsQuality() {
        assertTrue(MarkdownNegotiationFilter.prefersMarkdown("text/markdown"));
        assertTrue(MarkdownNegotiationFilter.prefersMarkdown("text/markdown, text/html;q=0.9"));
        assertFalse(MarkdownNegotiationFilter.prefersMarkdown(null));
        assertFalse(MarkdownNegotiationFilter.prefersMarkdown("*/*"));
        assertFalse(MarkdownNegotiationFilter.prefersMarkdown("text/markdown;q=0"));
        assertFalse(MarkdownNegotiationFilter.prefersMarkdown("text/html, text/markdown;q=0.5"));
        assertFalse(MarkdownNegotiationFilter.prefersMarkdown("text/markdown;q=broken"));
    }

    @Test
    void excludesApisAndAssetsFromNegotiation() {
        for (String path : new String[]{"", "/", "post/hello", "zh/post/hello", "category/java", "terms", "search"}) {
            assertTrue(MarkdownNegotiationFilter.isPublicPage(path), path);
        }
        for (String path : new String[]{"api/user/post/hello", "admin", "q/health", "search/suggest", "app.js", ".well-known/agent.json"}) {
            assertFalse(MarkdownNegotiationFilter.isPublicPage(path), path);
        }
    }

    @Test
    void preservesContentStructureWithoutPageChrome() {
        String markdown = MarkdownNegotiationFilter.toMarkdown("<nav>Menu</nav><main><h1>Title</h1><p>Hello <a href='/post/example'>link</a></p><pre><code>print(1)</code></pre><script>alert(1)</script><form>Login</form></main><footer>Footer</footer>");
        assertTrue(markdown.contains("# Title") || markdown.contains("Title\n====="));
        assertTrue(markdown.contains("[link](/post/example)"));
        assertTrue(markdown.contains("print(1)"));
        for (String removed : new String[]{"Menu", "Footer", "alert", "Login", "<main>"}) {
            assertFalse(markdown.contains(removed));
        }
    }
}
