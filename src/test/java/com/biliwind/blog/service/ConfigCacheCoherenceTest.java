package com.biliwind.blog.service;

import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the commit-then-publish rule: a ConfigChangedEvent fired inside a
 * transaction must not touch L1 or Redis unless that transaction commits.
 */
@QuarkusTest
class ConfigCacheCoherenceTest {

    private static final String CACHE_PREFIX = "windblog:config:";

    @Inject
    ConfigManager configManager;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    Event<ConfigChangedEvent> configChangedEvent;

    @Inject
    RedisDataSource redisDataSource;

    @Inject
    ObjectMapper mapper;

    @Test
    void rolledBackConfigChangeNeverReachesL1OrRedis() {
        String key = "__coherence_rollback_" + UUID.randomUUID();
        ObjectNode rejected = mapper.createObjectNode().put("flag", "rejected");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QuarkusTransaction.requiringNew().run(() -> {
                    configChangedEvent.fire(new ConfigChangedEvent(key, rejected));
                    throw new IllegalStateException("simulated audit failure");
                }));
        assertEquals("simulated audit failure", failure.getMessage());

        assertAll(
                () -> assertNull(configManager.getLocal(key), "rollback must not publish to L1"),
                () -> assertNull(redisValue(key), "rollback must not publish to Redis"),
                () -> assertNull(systemConfigService.getConfig(key)));
    }

    @Test
    void committedConfigChangeReachesL1AndRedisAfterCommit() throws Exception {
        String key = "__coherence_commit_" + UUID.randomUUID();
        ObjectNode accepted = mapper.createObjectNode().put("flag", "accepted");
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    configChangedEvent.fire(new ConfigChangedEvent(key, accepted)));

            assertNotNull(configManager.getLocal(key), "AFTER_SUCCESS must refresh L1");
            JsonNode cached = redisValue(key);
            assertNotNull(cached, "AFTER_SUCCESS must refresh Redis");
            assertEquals("accepted", cached.path("flag").asText());
        } finally {
            configManager.applyRemoteConfigChange(key, null);
            redisDataSource.value(String.class).getdel(CACHE_PREFIX + key);
        }
    }

    private JsonNode redisValue(String key) {
        String cached = redisDataSource.value(String.class).get(CACHE_PREFIX + key);
        if (cached == null) {
            return null;
        }
        try {
            return mapper.readTree(cached);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
