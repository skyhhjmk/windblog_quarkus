package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.model.ArticleMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexControllerUnitTest {

    private IndexController controller;
    private LanguageContext languageContext;

    @BeforeEach
    void setUp() throws Exception {
        controller = new IndexController();
        languageContext = new LanguageContext();
        setField(controller, "languageContext", languageContext);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testNormalizeArticleMode() {
        assertEquals("all", controller.normalizeArticleMode(null));
        assertEquals("all", controller.normalizeArticleMode(""));
        assertEquals("all", controller.normalizeArticleMode("   "));
        assertEquals("all", controller.normalizeArticleMode("all"));
        assertEquals("all", controller.normalizeArticleMode("ALL"));
        assertEquals("all", controller.normalizeArticleMode("invalid_mode"));

        assertEquals("standard", controller.normalizeArticleMode("standard"));
        assertEquals("standard", controller.normalizeArticleMode("STANDARD"));
        assertEquals("standard", controller.normalizeArticleMode(" Standard "));

        assertEquals("tutorial", controller.normalizeArticleMode("tutorial"));
        assertEquals("tutorial", controller.normalizeArticleMode("TUTORIAL"));

        assertEquals("resource", controller.normalizeArticleMode("resource"));
        assertEquals("resource", controller.normalizeArticleMode("RESOURCE"));
    }

    @Test
    void testParseFilterArticleMode() {
        assertNull(controller.parseFilterArticleMode("all"));
        assertNull(controller.parseFilterArticleMode(null));
        assertNull(controller.parseFilterArticleMode("other"));

        assertEquals(ArticleMode.STANDARD, controller.parseFilterArticleMode("standard"));
        assertEquals(ArticleMode.TUTORIAL, controller.parseFilterArticleMode("tutorial"));
        assertEquals(ArticleMode.RESOURCE, controller.parseFilterArticleMode("resource"));
    }

    @Test
    void testBuildIndexPageUrl() {
        // Page 1 without mode
        assertEquals("/", controller.buildIndexPageUrl(1, "all"));
        assertEquals("/", controller.buildIndexPageUrl(1, null));

        // Page 1 with mode
        assertEquals("/?mode=tutorial", controller.buildIndexPageUrl(1, "tutorial"));
        assertEquals("/?mode=resource", controller.buildIndexPageUrl(1, "resource"));
        assertEquals("/?mode=standard", controller.buildIndexPageUrl(1, "standard"));

        // Page > 1 without mode
        assertEquals("/page/2", controller.buildIndexPageUrl(2, "all"));
        assertEquals("/page/3", controller.buildIndexPageUrl(3, null));

        // Page > 1 with mode
        assertEquals("/page/2?mode=tutorial", controller.buildIndexPageUrl(2, "tutorial"));
        assertEquals("/page/5?mode=resource", controller.buildIndexPageUrl(5, "resource"));
    }

    @Test
    void testBuildPaginationPagesWithMode() {
        List<PaginationPage> pages = controller.buildPaginationPages(2, 5, "tutorial");
        assertEquals(5, pages.size());

        assertEquals(1, pages.get(0).number());
        assertEquals("/?mode=tutorial", pages.get(0).url());
        assertFalse(pages.get(0).current());

        assertEquals(2, pages.get(1).number());
        assertEquals("/page/2?mode=tutorial", pages.get(1).url());
        assertTrue(pages.get(1).current());

        assertEquals(3, pages.get(2).number());
        assertEquals("/page/3?mode=tutorial", pages.get(2).url());
        assertFalse(pages.get(2).current());
    }

    @Test
    void testBuildPaginationPagesWithoutMode() {
        List<PaginationPage> pages = controller.buildPaginationPages(1, 3, "all");
        assertEquals(3, pages.size());

        assertEquals(1, pages.get(0).number());
        assertEquals("/", pages.get(0).url());
        assertTrue(pages.get(0).current());

        assertEquals(2, pages.get(1).number());
        assertEquals("/page/2", pages.get(1).url());
        assertFalse(pages.get(1).current());
    }
}
