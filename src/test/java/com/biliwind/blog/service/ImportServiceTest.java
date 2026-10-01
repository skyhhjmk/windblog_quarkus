package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ImportServiceTest {

    @Test
    void shouldRedactCredentialsFromImportErrors() {
        String message = ImportService.sanitizeErrorMessage(
                "connect jdbc:postgresql://import-user:plain-secret@db.example/app?password=url-secret&apiKey=key-secret");

        assertFalse(message.contains("plain-secret"));
        assertFalse(message.contains("url-secret"));
        assertFalse(message.contains("key-secret"));
        assertTrue(message.contains("[REDACTED]"));
    }

    @Test
    void shouldBoundImportErrorLength() {
        String message = ImportService.sanitizeErrorMessage("x".repeat(600));

        assertTrue(message.length() <= 503);
        assertTrue(message.endsWith("..."));
    }

    @Test
    void shouldRedactCredentialsWhenAnImportUrlIsIncludedInAnEvent() {
        String message = ImportService.sanitizeErrorMessage(
                "同步媒体资源: https://sync-user:sync-secret@assets.example/file.png?token=url-token");

        assertFalse(message.contains("sync-secret"));
        assertFalse(message.contains("url-token"));
        assertTrue(message.contains("[REDACTED]"));
    }

    @Test
    void shouldNotPrefixAnAbsoluteDiscoveredMediaUrl() throws Exception {
        Method formatUrl = ImportService.class.getDeclaredMethod("formatUrl", String.class, String.class);
        formatUrl.setAccessible(true);
        String result = (String) formatUrl.invoke(new ImportService(),
                "https://www.biliwind.com", "https://tcimg.cn/image.png");
        assertEquals("https://tcimg.cn/image.png", result);
    }

    @Test
    void shouldDecodePostgresCopyTextEscapesAndNulls() throws Exception {
        Method decode = ImportService.class.getDeclaredMethod("decodeCopyText", String.class);
        decode.setAccessible(true);

        assertEquals("line one\nline two\tend", decode.invoke(new ImportService(), "line one\\nline two\\tend"));
        assertEquals(null, decode.invoke(new ImportService(), "\\N"));
        assertEquals("semi;colon", decode.invoke(new ImportService(), "semi;colon"));
    }

    @Test
    void shouldRejectSqlExpressionsInsideInsertValues() throws Exception {
        Method validator = ImportService.class.getDeclaredMethod("containsOnlySqlLiterals", String.class);
        validator.setAccessible(true);

        assertEquals(true, validator.invoke(new ImportService(), "('title', 42, NULL, TRUE)"));
        assertEquals(false, validator.invoke(new ImportService(), "(nextval('users_id_seq'))"));
        assertEquals(false, validator.invoke(new ImportService(), "(1 + 2)"));
    }

    @Test
    void shouldKeepCopyPayloadTogetherWhenItContainsSemicolons() throws Exception {
        Method split = ImportAnalysisService.class.getDeclaredMethod("splitStatements", String.class);
        split.setAccessible(true);
        String dump = "COPY public.posts (id, title) FROM stdin;\n1\tA; title\n2\tB\n\\.\n"
                + "INSERT INTO public.tags (id, name) VALUES (1, 'tag');";

        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) split.invoke(new ImportAnalysisService(), dump);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("A; title"));
        assertTrue(statements.get(0).endsWith("\\."));
        assertTrue(statements.get(1).startsWith("INSERT INTO"));
    }

    @Test
    void shouldMapLegacyCommentStatusesWithoutPublishingSpamOrTrash() throws Exception {
        Method mapper = ImportService.class.getDeclaredMethod("mapLegacyCommentStatus", String.class);
        mapper.setAccessible(true);

        assertEquals((short) 1, mapper.invoke(new ImportService(), "approved"));
        assertEquals((short) 0, mapper.invoke(new ImportService(), "waiting"));
        assertEquals((short) 2, mapper.invoke(new ImportService(), "spam"));
        assertEquals((short) 2, mapper.invoke(new ImportService(), "trash"));

        Method postStatus = ImportService.class.getDeclaredMethod("mapStatus", String.class);
        postStatus.setAccessible(true);
        assertEquals(com.biliwind.blog.model.PostStatus.PUBLISHED,
                postStatus.invoke(new ImportService(), "publish"));
        assertEquals(com.biliwind.blog.model.PostStatus.DRAFT,
                postStatus.invoke(new ImportService(), "pending"));
    }

    @Test
    void shouldAnalyzePostgresCopyAsSupportedLegacySql(@TempDir Path tempDir) throws Exception {
        String dump = "COPY public.categories (id, name, slug) FROM stdin;\n1\tNews\tnews\n\\.\n";
        Path file = tempDir.resolve("legacy.sql");
        Files.writeString(file, dump);
        Method analyze = ImportAnalysisService.class.getDeclaredMethod("analyzeSql", Path.class, long.class, String.class);
        analyze.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) analyze.invoke(
                new ImportAnalysisService(), file, (long) dump.length(), "legacy.sql");

        assertEquals(List.of(), report.get("blockers"));
        Map<?, ?> tables = assertInstanceOf(Map.class, report.get("tables"));
        Map<?, ?> categories = assertInstanceOf(Map.class, tables.get("categories"));
        assertEquals(1, categories.get("rows"));
    }
}
