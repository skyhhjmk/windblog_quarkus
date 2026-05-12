package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class StorageProviderConfigTest {

    @Test
    public void testDefaultConfig() {
        ArrayList<String> types = new ArrayList<>();
        types.add("*");
        StorageProviderConfig config = new StorageProviderConfig(null, types, null, false);
        assertNull(config.getConfigJson());
        assertEquals(1, config.getSupportedTypes().size());
        assertEquals("*", config.getSupportedTypes().get(0));
        assertNull(config.getCdnDomain());
        assertFalse(config.isCdnEnabled());
    }

    @Test
    public void testParseFromEntityJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String configJson = "{\"bucketName\":\"test-bucket\",\"region\":\"cn-hangzhou\"}";
        String supportedTypes = "[\"image/jpeg\",\"image/png\",\"image/webp\"]";

        StorageProviderConfig config = StorageProviderConfig.fromEntityJson(
                configJson, supportedTypes, "cdn.example.com", true, mapper);

        assertNotNull(config.getConfigJson());
        assertEquals("test-bucket", config.getConfigJson().get("bucketName").asText());
        assertEquals(3, config.getSupportedTypes().size());
        assertEquals("cdn.example.com", config.getCdnDomain());
        assertTrue(config.isCdnEnabled());
    }

    @Test
    public void testParseWithNullSupportedTypes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StorageProviderConfig config = StorageProviderConfig.fromEntityJson(
                null, null, null, false, mapper);

        assertEquals(1, config.getSupportedTypes().size());
        assertEquals("*", config.getSupportedTypes().get(0));
    }
}
