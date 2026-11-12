package com.biliwind.blog.service;

import com.biliwind.blog.service.settings.RegionalSettings;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeoManagementServiceTest {
    private final SeoManagementService service = new SeoManagementService();

    @Test void redirectsAcceptOnlySafeLocalContentPaths() {
        assertEquals("/post/example", service.validatePath("/post/example"));
        assertEquals("/en-US/post/example", service.validatePath("/en-US/post/example"));
        for (String path : new String[]{"https://evil.test", "//evil.test", "/api/admin/posts", "/admin", "/q/health", "/assets/main.js", "/robots.txt", "/sitemap.xml", "/post/../admin", "/post/%2e%2e/admin", "/post/x?token=secret", "/post/x#fragment", "/post/\\evil", "/post/x\r\nLocation:evil"}) {
            assertThrows(BadRequestException.class, () -> service.validatePath(path), path);
        }
    }

    @Test void rejectsLoopsAndUnsupportedStatusesBeforeDatabaseAccess() {
        assertThrows(BadRequestException.class, () -> service.saveRedirect(null, "/same", "/same", 301, true));
        assertThrows(BadRequestException.class, () -> service.saveRedirect(null, "/old", "/new", 200, true));
    }

    @Test void linkInterstitialIsNoindexWhileRegularPagesStayIndexable() {
        ConfigManager config = new ConfigManager() {
            @Override public JsonNode get(String key) { return null; }
        };
        service.settings = new SettingsReader(config, new RegionalSettings(config));
        assertEquals("noindex,follow", service.robots("/link/go/token"));
        assertEquals("index,follow", service.robots("/tools"));
        assertEquals("noindex,follow", service.robots("/search"));
    }
}
