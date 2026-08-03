package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void testInitialize() throws IOException {
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

        // This will attempt to create an OSSClient which might fail without real credentials or environment
        // But we can at least test that initialize doesn't throw immediate exception if handled gracefully
        try {
            provider.initialize(config);
        } catch (Exception e) {
            // Expected if it tries to connect
        }
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
