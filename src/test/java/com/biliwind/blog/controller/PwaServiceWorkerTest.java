package com.biliwind.blog.controller;

import com.biliwind.blog.service.SeoManagementService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PwaServiceWorkerTest {

    @Test
    void serviceWorkerScriptSelfDestructsAndClearsAllClientCaches() throws IOException {
        String sw = Files.readString(
                Path.of("src/main/resources/META-INF/resources/sw.js"),
                StandardCharsets.UTF_8);

        assertTrue(sw.contains("self.skipWaiting()"),
                "sw.js must immediately replace any previously installed Service Worker");
        assertTrue(sw.contains("caches.keys()") && sw.contains("caches.delete"),
                "sw.js must purge all previously created Cache Storage entries");
        assertTrue(sw.contains("self.registration.unregister()"),
                "sw.js must actively unregister itself on activation");
        assertFalse(sw.contains("addEventListener('fetch'"),
                "Self-destroying sw.js must not intercept fetch requests");
    }

    @Test
    void clientScriptsActivelyUnregisterServiceWorkersAndPurgeWindblogCaches() throws IOException {
        String legacyClientScript = Files.readString(
                Path.of("src/main/resources/META-INF/resources/assets/js/sw-register.js"),
                StandardCharsets.UTF_8);

        assertFalse(legacyClientScript.contains("navigator.serviceWorker.register"),
                "sw-register.js must no longer register a Service Worker");
        assertTrue(legacyClientScript.contains("navigator.serviceWorker.getRegistrations")
                        && legacyClientScript.contains("reg.unregister()"),
                "sw-register.js must actively unregister existing Service Worker registrations");
        assertTrue(legacyClientScript.contains("caches.delete"),
                "sw-register.js must delete residual windblog-* Cache Storage buckets");

        String mainJs = Files.readString(
                Path.of("src/main/resources/META-INF/resources/assets/js/main.js"),
                StandardCharsets.UTF_8);
        assertTrue(mainJs.contains("navigator.serviceWorker.getRegistrations")
                        && mainJs.contains("reg.unregister()"),
                "main.js must actively unregister residual Service Workers on page load");
    }

    @Test
    void seoServiceTreatsServiceWorkerPathAsProtectedSystemPath() {
        SeoManagementService seoService = new SeoManagementService();
        assertThrows(BadRequestException.class, () -> seoService.validatePath("/sw.js"));
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
