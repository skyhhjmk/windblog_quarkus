package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(PublicCacheWarmupWithoutRedisTest.NoRedisProfile.class)
class PublicCacheWarmupWithoutRedisTest {

    /** Points the optional cache at a closed port; the app must still boot and warm silently. */
    public static class NoRedisProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.redis.hosts", "redis://127.0.0.1:1");
        }
    }

    @Inject
    CacheService cacheService;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Test
    void warmUpDegradesSilentlyWhenRedisIsDown() {
        assertDoesNotThrow(publicCacheRefreshService::warmPublicCategoryTagCaches);
        assertFalse(cacheService.isAvailable());
    }
}
