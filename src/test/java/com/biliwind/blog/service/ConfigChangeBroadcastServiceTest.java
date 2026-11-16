package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigChangeBroadcastServiceTest {

    @Test
    void subscribeRegistersHandlerBeforeSendingSubscribeCommandAndAppliesRemoteChanges() {
        List<String> events = new ArrayList<>();
        Map<String, JsonNode> applied = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();

        FakeRedisConnection connection = new FakeRedisConnection(events);
        Redis fakeRedis = new Redis((io.vertx.redis.client.Redis) null) {
            @Override
            public Uni<RedisConnection> connect() {
                events.add("connect");
                return Uni.createFrom().item(connection);
            }
        };
        RedisDataSource fakeDataSource = (RedisDataSource) Proxy.newProxyInstance(
                RedisDataSource.class.getClassLoader(),
                new Class<?>[]{RedisDataSource.class},
                (proxy, method, args) -> {
                    if ("pubsub".equals(method.getName())) {
                        return Proxy.newProxyInstance(
                                PubSubCommands.class.getClassLoader(),
                                new Class<?>[]{PubSubCommands.class},
                                (p, m, a) -> null);
                    }
                    return null;
                });

        ConfigManager configManager = new ConfigManager() {
            @Override
            public void applyRemoteConfigChange(String key, JsonNode newValue) {
                applied.put(key, newValue);
            }
        };

        ConfigChangeBroadcastService service = new ConfigChangeBroadcastService();
        service.redisDataSource = fakeDataSource;
        service.redisClient = fakeRedis;
        service.objectMapper = mapper;
        service.configManager = configManager;

        service.subscribe();

        assertEquals(List.of("connect", "handler", "send:subscribe"), events);
        assertNotNull(connection.handler);

        // Simulate receiving the initial [subscribe, windblog:config:changed, 1] confirmation frame
        connection.handler.accept(multiResponse("subscribe", "windblog:config:changed", "1"));
        assertTrue(applied.isEmpty());

        // Simulate receiving a remote node's config change message
        ObjectNode payload = mapper.createObjectNode();
        payload.put("source", "remote-node");
        payload.put("key", "site_info");
        payload.set("value", mapper.createObjectNode().put("title", "WindBlog"));
        connection.handler.accept(multiResponse("message", "windblog:config:changed", payload.toString()));

        assertEquals("WindBlog", applied.get("site_info").path("title").asText());

        service.unsubscribe();
        assertTrue(connection.closed);
    }

    @Test
    void subscribeClosesConnectionWhenSubscribeCommandFails() {
        List<String> events = new ArrayList<>();
        FakeRedisConnection connection = new FakeRedisConnection(events);
        connection.failOnSend = true;

        Redis fakeRedis = new Redis((io.vertx.redis.client.Redis) null) {
            @Override
            public Uni<RedisConnection> connect() {
                return Uni.createFrom().item(connection);
            }
        };
        RedisDataSource fakeDataSource = (RedisDataSource) Proxy.newProxyInstance(
                RedisDataSource.class.getClassLoader(),
                new Class<?>[]{RedisDataSource.class},
                (proxy, method, args) -> null);

        ConfigChangeBroadcastService service = new ConfigChangeBroadcastService();
        service.redisDataSource = fakeDataSource;
        service.redisClient = fakeRedis;
        service.objectMapper = new ObjectMapper();
        service.configManager = new ConfigManager();

        service.subscribe();

        assertFalse(connection.handler == null);
        assertTrue(connection.closed);
    }

    private static Response multiResponse(String... items) {
        List<Response> children = new ArrayList<>(items.length);
        for (String item : items) {
            children.add(new Response((io.vertx.redis.client.Response) null) {
                @Override
                public String toString() {
                    return item;
                }
            });
        }
        return new Response((io.vertx.redis.client.Response) null) {
            @Override
            public int size() {
                return children.size();
            }

            @Override
            public Response get(int index) {
                return children.get(index);
            }
        };
    }

    private static final class FakeRedisConnection extends RedisConnection {
        private final List<String> events;
        private Consumer<Response> handler;
        private boolean failOnSend;
        private boolean closed;

        private FakeRedisConnection(List<String> events) {
            super((io.vertx.redis.client.RedisConnection) null);
            this.events = events;
        }

        @Override
        public RedisConnection handler(Consumer<Response> handler) {
            events.add("handler");
            this.handler = handler;
            return this;
        }

        @Override
        public RedisConnection endHandler(Runnable endHandler) {
            return this;
        }

        @Override
        public RedisConnection exceptionHandler(Consumer<Throwable> handler) {
            return this;
        }

        @Override
        public Uni<Response> send(Request request) {
            events.add("send:" + request.command().toString());
            if (failOnSend) {
                return Uni.createFrom().failure(new IllegalStateException("subscribe failed"));
            }
            return Uni.createFrom().nullItem();
        }

        @Override
        public RedisConnection closeAndForget() {
            closed = true;
            return this;
        }
    }
}
