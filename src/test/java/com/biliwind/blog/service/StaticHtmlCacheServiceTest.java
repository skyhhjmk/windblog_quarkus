package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StaticHtmlCacheServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesRealHtmlFilesTracksHitsAndSupportsPurge() throws Exception {
        StaticHtmlCacheService service = new StaticHtmlCacheService();
        service.initForTest(tempDir, true);

        String path = service.normalizeCacheablePath("/post/hello-world/", Map.of());
        assertEquals("/post/hello-world", path);

        String fullKey = service.buildKey("zh-CN", "GLOBAL", "full", path);
        String pjaxKey = service.buildKey("zh-CN", "GLOBAL", "pjax", path);
        assertNull(service.getIfPresent(fullKey));
        service.recordMiss(fullKey);

        service.put(fullKey, path, "zh-CN", "GLOBAL", "full", "<html><body>Hello</body></html>", "W/\"v1\"");
        service.put(pjaxKey, path, "zh-CN", "GLOBAL", "pjax", "<div>Fragment</div>", "W/\"p1\"");

        // Verify real .html files exist on disk (full + pjax)
        try (var stream = Files.list(tempDir.resolve("pages"))) {
            List<Path> files = stream.toList();
            assertEquals(2, files.size());
            assertTrue(files.stream().allMatch(p -> p.getFileName().toString().endsWith(".html")));
        }

        StaticHtmlCacheService.CachedDocument hit1 = service.getIfPresent(fullKey);
        assertNotNull(hit1);
        assertEquals("W/\"v1\"", hit1.etag());
        assertEquals("full", hit1.variant());

        StaticHtmlCacheService.CachedDocument hit2 = service.getIfPresent(fullKey);
        assertNotNull(hit2);

        StaticHtmlCacheService.CachedDocument pjaxHit = service.getIfPresent(pjaxKey);
        assertNotNull(pjaxHit);
        assertEquals("pjax", pjaxHit.variant());
        assertEquals("<div>Fragment</div>", new String(pjaxHit.html(), StandardCharsets.UTF_8));

        StaticHtmlCacheService.StaticCacheStatus status = service.getStatusSnapshot();
        assertTrue(status.enabled());
        assertEquals(2, status.entryCount());
        assertEquals(3L, status.totalHits());
        assertEquals(1L, status.totalMisses());

        // Flush pending stats to metadata and reload from disk
        service.syncStatsToDatabase();
        StaticHtmlCacheService reloaded = new StaticHtmlCacheService();
        reloaded.initForTest(tempDir, true);
        StaticHtmlCacheService.StaticCacheStatus reloadedStatus = reloaded.getStatusSnapshot();
        assertEquals(2, reloadedStatus.entryCount());
        assertEquals(3L, reloadedStatus.totalHits());

        // Targeted purge by path prefix removes both full and pjax variants
        int purged = reloaded.invalidateByPathPrefix("/post/");
        assertEquals(2, purged);
        assertEquals(0, reloaded.getStatusSnapshot().entryCount());

        // Re-populate and test one-click purge all
        reloaded.put(fullKey, path, "zh-CN", "GLOBAL", "full", "<html><body>Again</body></html>", "W/\"v2\"");
        assertEquals(1, reloaded.getStatusSnapshot().entryCount());
        assertEquals(1, reloaded.invalidateAll());
        assertEquals(0, reloaded.getStatusSnapshot().entryCount());
    }

    @Test
    void rejectsUncacheableRoutesAndArbitraryQueryStrings() {
        StaticHtmlCacheService service = new StaticHtmlCacheService();
        service.initForTest(tempDir, true);

        assertNull(service.normalizeCacheablePath("/user/center", Map.of()));
        assertNull(service.normalizeCacheablePath("/admin/posts", Map.of()));
        assertNull(service.normalizeCacheablePath("/search", Map.of("q", List.of("java"))));
        assertNull(service.normalizeCacheablePath("/post/hello", Map.of("utm_source", List.of("x"))));
        assertEquals("/?mode=tutorial", service.normalizeCacheablePath("/", Map.of("mode", List.of("tutorial"))));
        assertEquals("/", service.normalizeCacheablePath("/", Map.of("lang", List.of("en-US"))));
    }
}
