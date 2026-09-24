package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class LocalFsStorageClassTest {

    @TempDir
    Path tempDir;

    private LocalFsStorageClass provider;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        provider = new LocalFsStorageClass();
        provider.setName("local-test");

        Path rootPath = tempDir.resolve("uploads");
        Files.createDirectories(rootPath);

        String configJson = "{\"rootPath\":\"" + rootPath.toString().replace("\\", "\\\\") + "\", \"baseUrl\":\"/uploads\"}";
        StorageClassConfig config = new StorageClassConfig(
                objectMapper.readTree(configJson),
                java.util.List.of("*"),
                null,
                false
        );
        provider.initialize(config);
    }

    @Test
    void testUploadAndDownload() throws IOException {
        String content = "hello storage";
        String targetPath = "test/hello.txt";
        InputStream is = new ByteArrayInputStream(content.getBytes());

        String storedPath = provider.upload(is, targetPath, "text/plain");
        assertEquals(targetPath, storedPath);

        assertTrue(provider.exists(targetPath));

        InputStream downloaded = provider.download(targetPath);
        String downloadedContent = new String(downloaded.readAllBytes());
        assertEquals(content, downloadedContent);
    }

    @Test
    void testDelete() throws IOException {
        String targetPath = "test/delete.txt";
        provider.upload(new ByteArrayInputStream("to delete".getBytes()), targetPath, "text/plain");
        assertTrue(provider.exists(targetPath));

        provider.delete(targetPath);
        assertFalse(provider.exists(targetPath));
    }

    @Test
    void testIsAvailable() {
        assertTrue(provider.isAvailable());
    }

    @Test
    void testPublicUrl() {
        String path = "images/logo.png";
        String url = provider.getPublicUrl(path);
        assertEquals("/uploads/images/logo.png", url);
    }

    @Test
    void encryptedBackupUsesPrivateSiblingAndHasNoPublicUrl() throws IOException {
        String key = "encrypted-backup/v1/original/123e4567-e89b-12d3-a456-426614174000.wbak";
        provider.upload(new ByteArrayInputStream("ciphertext".getBytes()), key,
                "application/octet-stream");
        assertTrue(provider.exists(key));
        assertFalse(Files.exists(tempDir.resolve("uploads").resolve(key)));
        assertNull(provider.getPublicUrl(key));
        assertThrows(StorageException.class, () -> provider.upload(
                new ByteArrayInputStream(new byte[0]), "../escape", "application/octet-stream"));
    }
}
