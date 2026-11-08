package com.biliwind.blog.controller;

import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.service.SeoManagementService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PwaServiceWorkerTest {

    @Test
    void serviceWorkerScriptImplementsMultiTierCachingAndPjaxIsolation() throws IOException {
        String sw = Files.readString(
                Path.of("src/main/resources/META-INF/resources/sw.js"),
                StandardCharsets.UTF_8);

        assertTrue(sw.contains("STATIC_CACHE"), "sw.js must define static asset cache");
        assertTrue(sw.contains("MEDIA_CACHE"), "sw.js must define media/cover cache");
        assertTrue(sw.contains("PJAX_CACHE"), "sw.js must define isolated PJAX fragment cache");
        assertTrue(sw.contains("PAGES_CACHE"), "sw.js must define full HTML page cache");
        assertTrue(sw.contains("API_CACHE"), "sw.js must define public read API cache");

        assertTrue(sw.contains("/__wb_sw_pjax__"),
                "PJAX fragments must use synthetic cache keys so they never collide with full HTML documents");
        assertTrue(sw.contains("X-WindBlog-Prefetch"),
                "sw.js must support smart intent prefetch requests");
        assertTrue(sw.contains("X-WindBlog-Private"),
                "sw.js must bypass caching for private or personalized responses");
        assertTrue(sw.contains("X-WindBlog-Resolved-Url"),
                "sw.js must preserve resolved URLs across synthetic Response objects");
        assertTrue(sw.contains("<!--WINDBLOG_PJAX_CONTENT_START-->")
                        && sw.contains("<!--WINDBLOG_PJAX_CONTENT_END-->"),
                "sw.js must support synthesizing full HTML pages from a cached shell and PJAX fragment");
        assertTrue(sw.contains("PREFETCH_URLS")
                        && sw.contains("CLEAR_DYNAMIC_CACHES")
                        && sw.contains("GET_CACHED_ARTICLES")
                        && sw.contains("GET_SW_STATUS"),
                "sw.js must handle client coordination messages");
    }

    @Test
    void clientRegistrationScriptProvidesSmartPrefetchAndOfflineHydration() throws IOException {
        String clientScript = Files.readString(
                Path.of("src/main/resources/META-INF/resources/assets/js/sw-register.js"),
                StandardCharsets.UTF_8);

        assertTrue(clientScript.contains("navigator.serviceWorker.register"),
                "sw-register.js must register the Service Worker");
        assertTrue(clientScript.contains("windblogTrustedTypesPolicy"),
                "sw-register.js must respect Trusted Types when creating the Service Worker script URL");
        assertTrue(clientScript.contains("PREFETCH_URLS"),
                "sw-register.js must dispatch smart prefetch tasks to the Service Worker");
        assertTrue(clientScript.contains("data-offline-cached-list"),
                "sw-register.js must hydrate offline cached article lists");
        assertTrue(clientScript.contains("CLEAR_DYNAMIC_CACHES"),
                "sw-register.js must invalidate dynamic caches on auth or language changes");
        assertTrue(clientScript.contains("window.WindBlogSW"),
                "sw-register.js must expose the public WindBlogSW helper API");
    }

    @Test
    void baseTemplateAndMainJsIntegrateWithServiceWorkerAndOfflineShell() throws IOException {
        String baseHtml = Files.readString(
                Path.of("src/main/resources/templates/base.html"),
                StandardCharsets.UTF_8);
        assertTrue(baseHtml.contains("rel=\"manifest\" href=\"/manifest.webmanifest\""),
                "base.html must link the Web App Manifest");
        assertTrue(baseHtml.contains("<!--WINDBLOG_PJAX_CONTENT_START-->")
                        && baseHtml.contains("<!--WINDBLOG_PJAX_CONTENT_END-->"),
                "base.html must include PJAX shell markers for offline page synthesis");
        assertTrue(baseHtml.contains("/assets/js/sw-register.js"),
                "base.html must load sw-register.js");

        String mainJs = Files.readString(
                Path.of("src/main/resources/META-INF/resources/assets/js/main.js"),
                StandardCharsets.UTF_8);
        assertTrue(mainJs.contains("X-WindBlog-Resolved-Url"),
                "main.js must read X-WindBlog-Resolved-Url when handling synthetic SW responses");
        assertTrue(mainJs.contains("X-WindBlog-SW-Source"),
                "main.js must propagate X-WindBlog-SW-Source to PJAX lifecycle events");
    }

    @Test
    void pwaControllerGeneratesValidWebManifestResponse() {
        PwaController controller = new PwaController();
        LanguageContext langCtx = new LanguageContext();
        langCtx.setLang("zh-CN");
        langCtx.setDefaultLang("zh-CN");
        controller.languageContext = langCtx;

        Response response = controller.manifest();
        assertEquals(200, response.getStatus());
        assertEquals("public, max-age=3600", response.getHeaderString("Cache-Control"));

        String body = (String) response.getEntity();
        assertNotNull(body);
        assertTrue(body.contains("\"name\":"));
        assertTrue(body.contains("\"short_name\":"));
        assertTrue(body.contains("\"start_url\": \"/\""));
        assertTrue(body.contains("\"display\": \"standalone\""));
        assertTrue(body.contains("\"/favicon.svg\""));
    }

    @Test
    void seoServiceTreatsServiceWorkerAndManifestAsProtectedSystemPaths() {
        SeoManagementService seoService = new SeoManagementService();
        assertThrows(BadRequestException.class, () -> seoService.validatePath("/sw.js"));
        assertThrows(BadRequestException.class, () -> seoService.validatePath("/manifest.webmanifest"));
    }

    @Test
    void rulerHudFlipAutoSnapUsesPerSectionScaleWithoutUndefinedTravelScale() throws IOException {
        String rulerHudJs = Files.readString(
                Path.of("src/main/resources/META-INF/resources/assets/js/ruler-hud.js"),
                StandardCharsets.UTF_8);

        assertFalse(rulerHudJs.contains("travelScale"),
                "ruler-hud.js must not reference removed travelScale variable");
        assertTrue(rulerHudJs.contains("secScale: secScale") && rulerHudJs.contains("st.secScale"),
                "ruler-hud.js must persist per-section secScale and use it during flip auto-snap");
    }

    @Test
    void webSerialTerminalRecoversPortStateOnOpenAndReadFailure() throws IOException {
        String toolsJs = Files.readString(
                Path.of("src/main/resources/META-INF/resources/assets/js/tools.js"),
                StandardCharsets.UTF_8);

        assertTrue(toolsJs.contains("openPortWithRecovery"),
                "tools.js must open serial ports through openPortWithRecovery");
        assertTrue(toolsJs.contains("forceClosePort"),
                "tools.js must release stream locks and close/forget failed serial ports");
        assertTrue(toolsJs.contains("port.forget()"),
                "tools.js must call port.forget() to reset Chromium SerialPort Mojo bindings after open/close failures");
    }
}
