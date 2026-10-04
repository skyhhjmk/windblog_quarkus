package com.biliwind.blog.service.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WespActivePollChannelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private WespActivePollChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        channel = new WespActivePollChannel();
        field("mapper", mapper);
        field("role", new NodeRoleService() {
            @Override public boolean isEdgeNode() { return true; }
            @Override public boolean isPrimaryNode() { return false; }
            @Override public String getNodeId() { return "public-edge"; }
        });
        field("sync", new WespSyncService() {
            @Override public boolean isEnabled() { return true; }
            @Override public void recordPeerSession(String remoteNodeId) { }
        });
        field("runtimeConfig", new WespRuntimeConfig() {
            @Override public boolean isActivePoll() { return true; }
        });
        field("readOnlyState", new EdgeReadOnlyState());
    }

    @AfterEach
    void tearDown() { channel.stop(); }

    @Test
    void edgeQueuesRequestUntilPrivatePrimaryPollsAndReturnsResult() throws Exception {
        CompletableFuture<Response> poll = channel.poll("private-primary");
        CompletableFuture<RoutedHttpExchange.Response> forwarded = CompletableFuture.supplyAsync(() ->
                channel.forward(new RoutedHttpExchange.Request("POST", "/api/example", "", Map.of(),
                        "hello".getBytes(StandardCharsets.UTF_8))));
        Response delivery = poll.get(3, TimeUnit.SECONDS);
        assertEquals(200, delivery.getStatus());
        ObjectNode command = (ObjectNode) delivery.getEntity();
        assertEquals("POST", command.path("method").asText());
        assertEquals("aGVsbG8=", command.path("body").asText());
        String id = command.path("request_id").asText();
        ObjectNode result = mapper.createObjectNode();
        result.put("status", 201);
        result.put("body", "b2s=");
        result.putObject("headers").put("Content-Type", "text/plain");
        assertFalse(channel.complete("other-primary", id, result));
        assertTrue(channel.complete("private-primary", id, result));
        RoutedHttpExchange.Response response = forwarded.get(3, TimeUnit.SECONDS);
        assertEquals(201, response.status());
        assertEquals("ok", new String(response.body(), StandardCharsets.UTF_8));
        delivery.close();
    }

    @Test
    void edgeFailsClosedWithoutARecentPrimaryPoll() {
        RoutedHttpExchange.Response response = channel.forward(new RoutedHttpExchange.Request(
                "POST", "/api/example", "", Map.of(), new byte[0]));
        assertEquals(503, response.status());
    }

    @Test
    void replacesAnExpiredPollAfterThePrimaryDisconnects() throws Exception {
        CompletableFuture<Response> abandoned = channel.poll("private-primary");
        field("lastPollMillis", System.currentTimeMillis() - 31_000);
        CompletableFuture<Response> replacement = channel.poll("private-primary");
        assertEquals(204, abandoned.get(3, TimeUnit.SECONDS).getStatus());
        assertFalse(replacement.isDone());
    }

    private void field(String name, Object value) throws Exception {
        Field target = WespActivePollChannel.class.getDeclaredField(name);
        target.setAccessible(true);
        target.set(channel, value);
    }
}
