package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AliyunOssStorageClassMockTest {

    private AliyunOssStorageClass provider;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        provider = new AliyunOssStorageClass();
        provider.setName("aliyun-test");
    }

    @Test
    void testInitializeSuccess() throws IOException {
        String configJson = "{" +
                "\"endpoint\":\"oss-cn-hangzhou.aliyuncs.com\"," +
                "\"accessKeyId\":\"test-ak\"," +
                "\"accessKeySecret\":\"test-sk\"," +
                "\"bucketName\":\"test-bucket\"," +
                "\"basePath\":\"media/\"" +
                "}";

        StorageClassConfig config = new StorageClassConfig(
                objectMapper.readTree(configJson),
                Collections.singletonList("image/*"),
                "cdn.example.com",
                true
        );

        assertDoesNotThrow(() -> provider.initialize(config));
        assertTrue(provider.supportsVariant("image/jpeg", "original"));
    }

    @Test
    void testInitializeFailsOnMissingRequiredConfig() {
        StorageClassConfig emptyConfig = new StorageClassConfig(
                objectMapper.createObjectNode(),
                Collections.emptyList(),
                null,
                false
        );
        assertThrows(StorageException.class, () -> provider.initialize(emptyConfig));
    }

    @Test
    void testSupportsVariant() throws IOException {
        String configJson = "{\"bucketName\":\"test\", \"accessKeyId\":\"test\", \"accessKeySecret\":\"test\"}";

        StorageClassConfig config1 = new StorageClassConfig(
                objectMapper.readTree(configJson),
                java.util.List.of("image/*"),
                null,
                false
        );
        provider.initialize(config1);
        assertTrue(provider.supportsVariant("image/png", "original"));
        assertFalse(provider.supportsVariant("video/mp4", "original"));

        StorageClassConfig config2 = new StorageClassConfig(
                objectMapper.readTree(configJson),
                java.util.List.of("*"),
                null,
                false
        );
        provider.initialize(config2);
        assertTrue(provider.supportsVariant("video/mp4", "original"));
    }

    @Test
    void shouldNotFallbackToPublicUrlWhenSigningFails() {
        assertNull(provider.getSignedUrl("protected/file.bin", Duration.ofMinutes(5)));
    }
}
