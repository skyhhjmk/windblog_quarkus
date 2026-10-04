package com.biliwind.blog.service.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A continuously renewed, primary-initiated WESP request channel. */
@ApplicationScoped
public class WespActivePollChannel {
    private static final Logger LOG = LoggerFactory.getLogger(WespActivePollChannel.class);
    private static final int MAX_BODY = 10_485_760;
    private static final int MAX_PENDING = 64;
    private static final Duration POLL_WAIT = Duration.ofSeconds(25);
    private static final Duration REQUEST_WAIT = Duration.ofSeconds(70);

    @Inject ObjectMapper mapper;
    @Inject NodeRoleService role;
    @Inject WespSyncService sync;
    @Inject PrimaryRoutedHttpExecutor executor;
    @Inject EdgeReadOnlyState readOnlyState;
    @Inject WespRuntimeConfig runtimeConfig;

    @ConfigProperty(name = "windblog.wesp.active-poll.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "windblog.wesp.active-poll.target-node-id")
    Optional<String> configuredTargetNodeId;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService driver = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wesp-active-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<ObjectNode>> waitingPoll = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Command> queued = new ConcurrentLinkedQueue<>();
    private final Map<String, Command> pending = new ConcurrentHashMap<>();
    private volatile long lastPollMillis;
    private volatile String connectedPrimaryId;

    public boolean isEnabled() { return (enabled || runtimeConfig.isActivePoll()) && sync.isEnabled(); }

    /** Called on the public edge. The held response is completed when work arrives. */
    public CompletableFuture<Response> poll(String primaryId) {
        if (!isEnabled() || !role.isEdgeNode()) {
            return CompletableFuture.completedFuture(Response.status(409).build());
        }
        CompletableFuture<ObjectNode> slot = new CompletableFuture<>();
        CompletableFuture<ObjectNode> previous = waitingPoll.get();
        if (previous != null && System.currentTimeMillis() - lastPollMillis > 30_000) {
            previous.complete(null);
            waitingPoll.compareAndSet(previous, null);
        }
        if (!waitingPoll.compareAndSet(null, slot)) {
            return CompletableFuture.completedFuture(Response.status(409).build());
        }
        connectedPrimaryId = primaryId;
        lastPollMillis = System.currentTimeMillis();
        readOnlyState.markPrimaryOnline();
        slot.completeOnTimeout(null, POLL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Response> response = slot.handle((command, failure) -> {
            waitingPoll.compareAndSet(slot, null);
            if (command == null) return Response.noContent().build();
            return Response.ok(command).build();
        });
        deliver();
        return response;
    }

    public RoutedHttpExchange.Response forward(RoutedHttpExchange.Request request) {
        if (!isEnabled() || !role.isEdgeNode() || request == null || request.body() != null
                && request.body().length > MAX_BODY) return unavailable();
        if (System.currentTimeMillis() - lastPollMillis > 35_000 || pending.size() >= MAX_PENDING) {
            readOnlyState.markPrimaryOffline("WESP 主节点主动通道不可用");
            return unavailable();
        }
        String id = UUID.randomUUID().toString();
        ObjectNode message = mapper.createObjectNode();
        message.put("request_id", id);
        message.put("target_node_id", role.getNodeId());
        message.put("method", request.method());
        message.put("path", request.path());
        message.put("query", request.query() == null ? "" : request.query());
        ObjectNode headers = message.putObject("headers");
        if (request.headers() != null) request.headers().forEach((key, value) -> {
            if (key != null && value != null) headers.put(key, value);
        });
        message.put("body", Base64.getEncoder().encodeToString(request.body() == null ? new byte[0] : request.body()));
        Command command = new Command(id, message, new CompletableFuture<>());
        pending.put(id, command);
        queued.add(command);
        deliver();
        try {
            return command.response.get(REQUEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            return unavailable();
        } finally {
            pending.remove(id, command);
            queued.remove(command);
        }
    }

    public boolean complete(String primaryId, String id, JsonNode result) {
        if (!isEnabled() || !role.isEdgeNode() || !primaryId.equals(connectedPrimaryId)) return false;
        Command command = pending.get(id);
        if (command == null || result == null || result.path("status").asInt(0) < 100
                || result.path("status").asInt(0) > 599) return false;
        try {
            byte[] body = Base64.getDecoder().decode(result.path("body").asText(""));
            if (body.length > MAX_BODY) return false;
            Map<String, String> headers = new java.util.HashMap<>();
            JsonNode headerNode = result.path("headers");
            if (headerNode.isObject()) headerNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) headers.put(entry.getKey(), entry.getValue().asText());
            });
            return command.response.complete(new RoutedHttpExchange.Response(
                    result.path("status").asInt(), headers, body, result.path("errorMessage").asText("")));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void deliver() {
        CompletableFuture<ObjectNode> slot = waitingPoll.get();
        if (slot == null || slot.isDone()) return;
        Command next = queued.poll();
        if (next != null && !slot.complete(next.message)) queued.add(next);
    }

    @Scheduled(every = "5s", identity = "wesp-active-poll-drive")
    void ensureConnected() {
        if (isEnabled() && role.isPrimaryNode() && sync.hasPeer() && running.compareAndSet(false, true)) {
            driver.execute(this::runPollLoop);
        }
        if (isEnabled() && role.isEdgeNode() && System.currentTimeMillis() - lastPollMillis > 35_000
                && readOnlyState.isPrimaryOnline()) {
            readOnlyState.markPrimaryOffline("WESP 主节点主动通道已断开");
        }
    }

    private void runPollLoop() {
        try {
            while (isEnabled() && role.isPrimaryNode() && sync.hasPeer() && !driver.isShutdown()) {
                try {
                    String peer = sync.peerUrlForActivePoll();
                    HttpRequest poll = request(peer + "/sync/v1/active-poll/next", null).GET().build();
                    HttpResponse<String> response = client.send(poll,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() == 200 || response.statusCode() == 204) {
                        runtimeConfig.peerNodeId().or(() -> configuredTargetNodeId)
                                .ifPresent(sync::recordPeerSession);
                    }
                    if (response.statusCode() == 200) executeCommand(peer, mapper.readTree(response.body()));
                    else if (response.statusCode() != 204) TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception exception) {
                    LOG.debug("WESP 主动通道重连中: {}", exception.getClass().getSimpleName());
                    TimeUnit.SECONDS.sleep(2);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
        }
    }

    private void executeCommand(String peer, JsonNode command) throws Exception {
        String id = command.path("request_id").asText("");
        String method = command.path("method").asText("").toUpperCase(java.util.Locale.ROOT);
        String path = command.path("path").asText("");
        if (!id.matches("[0-9a-fA-F-]{36}")) return;
        boolean fullSync = "WESP_FULL_SYNC".equals(method) && "/internal/full-sync".equals(path);
        if (!fullSync && (!java.util.Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE").contains(method)
                || !path.startsWith("/") || path.startsWith("/sync/") || path.startsWith("//"))) return;
        byte[] body = Base64.getDecoder().decode(command.path("body").asText(""));
        if (body.length > MAX_BODY) return;
        Map<String, String> headers = new java.util.HashMap<>();
        JsonNode headerNode = command.path("headers");
        if (headerNode.isObject()) headerNode.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) headers.put(entry.getKey(), entry.getValue().asText());
        });
        RoutedHttpExchange.Response result;
        if (fullSync) {
            sync.triggerFullSync(command.path("target_node_id").asText(""),
                    "force=true".equals(command.path("query").asText("")));
            result = new RoutedHttpExchange.Response(202, Map.of(), new byte[0], "");
        } else {
            result = executor.execute(new RoutedHttpExchange.Request(
                    method, path, command.path("query").asText(""), headers, body));
        }
        ObjectNode reply = mapper.createObjectNode();
        reply.put("status", result.status());
        reply.put("body", Base64.getEncoder().encodeToString(result.body() == null ? new byte[0] : result.body()));
        reply.put("errorMessage", result.errorMessage() == null ? "" : result.errorMessage());
        ObjectNode responseHeaders = reply.putObject("headers");
        if (result.headers() != null) result.headers().forEach(responseHeaders::put);
        HttpResponse<String> response = client.send(request(peer + "/sync/v1/active-poll/results/" + id,
                mapper.writeValueAsString(reply)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reply)))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 204) LOG.debug("WESP 主动通道结果未被接受: HTTP {}", response.statusCode());
    }

    private HttpRequest.Builder request(String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + sync.bootstrapAuthToken())
                .header("X-WESP-Node-Id", role.getNodeId())
                .header("X-WESP-Request-Id", UUID.randomUUID().toString())
                .header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        return builder;
    }

    private static RoutedHttpExchange.Response unavailable() {
        return new RoutedHttpExchange.Response(503, Map.of("Content-Type", "application/json"),
                "{\"success\":false,\"message\":\"WESP 主节点主动通道不可用\"}"
                        .getBytes(StandardCharsets.UTF_8), "WESP 主节点主动通道不可用");
    }

    @PreDestroy
    void stop() {
        driver.shutdownNow();
        pending.values().forEach(command -> command.response.complete(unavailable()));
    }

    private record Command(String id, ObjectNode message, CompletableFuture<RoutedHttpExchange.Response> response) { }
}
